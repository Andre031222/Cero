#!/usr/bin/env python3
"""Los ataques de HTTP/2 Bomb y los plazos, en bytes crudos, contra cualquier implementación.

A diferencia de correr.py, este arranca el servidor él mismo, porque necesita plazos cortos y
porque uno de los requisitos es justo que el servidor se niegue a arrancar:

    python3 spec/banco/ataques.py ./c++/build/conforme
    python3 spec/banco/ataques.py ./rust/target/release/conforme
    python3 spec/banco/ataques.py java -cp <clases> cero.http.Conforme
    python3 spec/banco/ataques.py --solo-http1 python3 -m cero.conforme

Con `--solo-http1` los vectores de HTTP/2 salen como «n/a» y no cuentan: es para una
implementación que todavía no habla h2.

A la orden se le añaden dos argumentos, `<puerto> <plazo en ms>`, y el servidor de conformidad
tiene que usar ese plazo para esperar crédito y para escribir, y el doble para la inactividad.
"""

from __future__ import annotations

import socket
import struct
import subprocess
import sys
import time
from collections import namedtuple

PLAZO = 1.0

PREAMBULO = b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
DATA, HEADERS, PRIORITY, RST_STREAM, SETTINGS, PING, GOAWAY, WINDOW_UPDATE = 0x0, 0x1, 0x2, 0x3, 0x4, 0x6, 0x7, 0x8
FIN_FLUJO, FIN_CABECERAS, ACK = 0x1, 0x4, 0x1
NO_ERROR, CANCEL, ENHANCE_YOUR_CALM = 0x0, 0x8, 0xB
VENTANA_INICIAL, MAX_HEADER_LIST_SIZE = 0x4, 0x6

Trama = namedtuple("Trama", "tipo banderas flujo carga")


def trama(tipo: int, banderas: int, flujo: int, carga: bytes) -> bytes:
    return struct.pack(">I", len(carga))[1:] + bytes([tipo, banderas]) + struct.pack(">I", flujo) + carga


def entero(valor: int, prefijo: int, banderas: int) -> bytes:
    tope = (1 << prefijo) - 1
    if valor < tope:
        return bytes([banderas | valor])
    salida = [banderas | tope]
    valor -= tope
    while valor >= 128:
        salida.append((valor & 0x7F) | 0x80)
        valor >>= 7
    return bytes(salida + [valor])


def cadena(texto: str) -> bytes:
    crudo = texto.encode("latin-1")
    return entero(len(crudo), 7, 0) + crudo


def a_la_tabla(nombre: str, valor: str) -> bytes:
    """Literal con indexación: queda en la tabla dinámica con el índice 62."""
    return b"\x40" + cadena(nombre) + cadena(valor)


REPITE_LA_ULTIMA = bytes([0x80 | 62])
GET_RAIZ = bytes([0x82, 0x86, 0x84])
COSTE_GET_RAIZ = (7 + 3 + 32) + (7 + 4 + 32) + (5 + 1 + 32)


def get(camino: str) -> bytes:
    return bytes([0x82, 0x86, 0x04]) + cadena(camino)


def goaway(codigo: int):
    return lambda t: t.tipo == GOAWAY and struct.unpack(">I", t.carga[4:8])[0] == codigo


def codigo_de(t: Trama) -> int:
    desde = 4 if t.tipo == GOAWAY else 0
    return struct.unpack(">I", t.carga[desde:desde + 4])[0]


class H2:
    def __init__(self, puerto: int, ajustes: bytes = b""):
        self.s = socket.create_connection(("127.0.0.1", puerto), timeout=5)
        self.buf = b""
        self.cerrada = False
        self.vistas: list[Trama] = []
        self.mandar(PREAMBULO + trama(SETTINGS, 0, 0, ajustes))
        primera = self.siguiente(time.monotonic() + 5)
        if primera is None or primera.tipo != SETTINGS:
            raise ConnectionError("el servidor no abrió con SETTINGS")
        c = primera.carga
        self.ajustes = {struct.unpack(">H", c[i:i + 2])[0]: struct.unpack(">I", c[i + 2:i + 6])[0]
                        for i in range(0, len(c), 6)}
        self.mandar(trama(SETTINGS, ACK, 0, b""))

    def mandar(self, octetos: bytes) -> None:
        try:
            self.s.sendall(octetos)
        except OSError:
            self.cerrada = True

    def siguiente(self, hasta: float):
        while len(self.buf) < 9 or len(self.buf) < 9 + int.from_bytes(self.buf[:3], "big"):
            queda = hasta - time.monotonic()
            if queda <= 0 or self.cerrada:
                return None
            self.s.settimeout(queda)
            try:
                trozo = self.s.recv(65536)
            except socket.timeout:
                return None
            except OSError:
                trozo = b""
            if not trozo:
                self.cerrada = True
                return None
            self.buf += trozo
        largo = int.from_bytes(self.buf[:3], "big")
        t = Trama(self.buf[3], self.buf[4], int.from_bytes(self.buf[5:9], "big") & 0x7FFFFFFF,
                  self.buf[9:9 + largo])
        self.buf = self.buf[9 + largo:]
        self.vistas.append(t)
        return t

    def vigilar(self, segundos: float, cumple, cada: float = 0, latido: bytes = b""):
        """Lee tramas hasta que una cumple; si se pide, manda el latido cada tanto mientras espera."""
        hasta = time.monotonic() + segundos
        proximo = time.monotonic() + cada if cada else hasta
        while time.monotonic() < hasta and not self.cerrada:
            if cada and time.monotonic() >= proximo:
                self.mandar(latido)
                proximo += cada
            t = self.siguiente(min(hasta, proximo))
            if t is not None and cumple(t):
                return t
        return None

    def visto(self) -> str:
        nombres = {DATA: "DATA", HEADERS: "HEADERS", RST_STREAM: "RST_STREAM", SETTINGS: "SETTINGS",
                   PING: "PING", GOAWAY: "GOAWAY", WINDOW_UPDATE: "WINDOW_UPDATE"}
        resumen = [nombres.get(t.tipo, hex(t.tipo)) + (f"({codigo_de(t)})" if t.tipo in (GOAWAY, RST_STREAM) else "")
                   for t in self.vistas[-6:]]
        return ", ".join(resumen) + (" y cerró" if self.cerrada else "")

    def cerrar(self) -> None:
        self.s.close()


INACTIVIDAD = 2 * PLAZO
CUERPO_DE_MAS = 11 << 20

DIGITOS = {"00000": "0", "00001": "1", "00010": "2", "011001": "3", "011010": "4",
           "011011": "5", "011100": "6", "011101": "7", "011110": "8", "011111": "9"}
ESTATICOS = {8: "200", 9: "204", 10: "206", 11: "304", 12: "400", 13: "404", 14: "500"}


def estado_h2(bloque: bytes) -> str:
    """El :status de una respuesta, que va primero: indexado o literal, en claro o en Huffman."""
    if bloque[0] & 0x80:
        return ESTATICOS.get(bloque[0] & 0x7F, "?")
    largo = bloque[1] & 0x7F
    crudo = bloque[2:2 + largo]
    if not bloque[1] & 0x80:
        return crudo.decode("latin-1")
    bits, texto = "".join(f"{b:08b}" for b in crudo), ""
    while bits:
        codigo = next((c for c in DIGITOS if bits.startswith(c)), None)
        if codigo is None:
            break
        texto += DIGITOS[codigo]
        bits = bits[len(codigo):]
    return texto


def con_cookies(campos: int) -> bytes:
    return GET_RAIZ + a_la_tabla("cookie", "a=1") + REPITE_LA_ULTIMA * (campos - 4)


def h2_050(puerto: int):
    c = H2(puerto)
    tope = c.ajustes.get(MAX_HEADER_LIST_SIZE, 0)
    coste = COSTE_GET_RAIZ + 300 * (6 + 3 + 32)
    if tope < coste:
        return False, f"anuncia una lista de {tope}: no cabrían ni los 303 campos, y el vector no distinguiría H2-050 de H2-051"
    c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 1, con_cookies(256)))
    respuesta = c.vigilar(3, lambda t: t.flujo == 1 or t.tipo == GOAWAY)
    if respuesta is None or respuesta.tipo != HEADERS:
        return False, f"256 campos, que es el tope, no se atienden: {c.visto()}"
    c.cerrar()
    c = H2(puerto)
    c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 1, con_cookies(303)))
    ok = c.vigilar(3, goaway(ENHANCE_YOUR_CALM)) is not None
    return ok, f"anuncia {tope}, atiende 256 campos y, con 303 en {coste} octetos de lista, {c.visto()}"


def h2_051(puerto: int):
    c = H2(puerto)
    tope = c.ajustes.get(MAX_HEADER_LIST_SIZE)
    if tope is None:
        return False, "no anuncia SETTINGS_MAX_HEADER_LIST_SIZE"
    valor = "v" * 4000
    coste = len("x-a") + len(valor) + 32
    caben = (tope - COSTE_GET_RAIZ) // coste

    def bloque(n: int) -> bytes:
        return GET_RAIZ + a_la_tabla("x-a", valor) + REPITE_LA_ULTIMA * (n - 1)

    c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 1, bloque(caben)))
    respuesta = c.vigilar(3, lambda t: t.flujo == 1 or t.tipo == GOAWAY)
    if respuesta is None or respuesta.tipo != HEADERS:
        return False, f"anuncia {tope} y rechaza {COSTE_GET_RAIZ + caben * coste}: {c.visto()}"
    c.cerrar()

    c = H2(puerto)
    c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 1, bloque(caben + 1)))
    ok = c.vigilar(3, goaway(ENHANCE_YOUR_CALM)) is not None
    return ok, f"anuncia {tope}, admite {COSTE_GET_RAIZ + caben * coste} y, con {COSTE_GET_RAIZ + (caben + 1) * coste}, {c.visto()}"


def h2_052(puerto: int):
    c = H2(puerto, struct.pack(">HI", VENTANA_INICIAL, 0))
    c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 1, GET_RAIZ))
    empezo = time.monotonic()
    goteo = trama(WINDOW_UPDATE, 0, 1, struct.pack(">I", 1))
    fin = c.vigilar(6 * PLAZO, lambda t: t.flujo == 1 and t.tipo in (RST_STREAM, DATA) and
                    (t.tipo == RST_STREAM or t.banderas & FIN_FLUJO), PLAZO / 4, goteo)
    tardo = time.monotonic() - empezo
    cortado = fin is not None and fin.tipo == RST_STREAM and codigo_de(fin) == CANCEL
    ok = cortado and tardo >= 0.75 * PLAZO
    return ok, f"un octeto de crédito cada {PLAZO / 4:.2f} s, corte a los {tardo:.1f} s (plazo {PLAZO:.0f} s): {c.visto()}"


def h2_053(puerto: int):
    c = H2(puerto)
    sin_trabajo = (trama(PING, 0, 0, b"latido!!") + trama(WINDOW_UPDATE, 0, 0, struct.pack(">I", 1))
                   + trama(SETTINGS, 0, 0, b"") + trama(PRIORITY, 0, 3, struct.pack(">IB", 0, 15)))
    empezo = time.monotonic()
    adios = c.vigilar(5 * PLAZO, goaway(NO_ERROR), PLAZO / 4, sin_trabajo)
    tardo = time.monotonic() - empezo
    ok = adios is not None and tardo >= 0.75 * INACTIVIDAD
    return ok, (f"PING, WINDOW_UPDATE, SETTINGS y PRIORITY cada {PLAZO / 4:.2f} s; GOAWAY a los "
                f"{tardo:.1f} s (inactividad {INACTIVIDAD:.0f} s): {c.visto()}")


def h2_054(puerto: int):
    sola = H2(puerto, struct.pack(">HI", VENTANA_INICIAL, 0))
    sola.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 1, get("/grande")))
    if sola.vigilar(0.5 * PLAZO, lambda t: t.tipo == GOAWAY) is not None or sola.cerrada:
        return False, f"una sola respuesta de 1 MiB ya pasa del presupuesto: {sola.visto()}"
    sola.cerrar()
    c = H2(puerto, struct.pack(">HI", VENTANA_INICIAL, 0))
    for flujo in range(1, 80, 2):
        c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, flujo, get("/grande")))
    ok = c.vigilar(3 * PLAZO, goaway(ENHANCE_YOUR_CALM)) is not None
    return ok, f"una respuesta de 1 MiB sí; 40 con la ventana a 0, {c.visto()}"


def post(camino: str, largo: int | None = None) -> bytes:
    bloque = bytes([0x83, 0x86, 0x04]) + cadena(camino)
    if largo is not None:
        bloque += bytes([0x0F, 0x0D]) + cadena(str(largo))
    return bloque


def subir(c: H2, flujo: int, total: int, hasta_que) -> tuple[int, Trama | None]:
    """Manda `total` octetos respetando el control de flujo, hasta que llegue una trama que cumpla."""
    ventana = {0: 65535, flujo: c.ajustes.get(VENTANA_INICIAL, 65535)}
    mandados = 0
    limite = time.monotonic() + 30
    while not c.cerrada and time.monotonic() < limite:
        trozo = min(16384, total - mandados, ventana[0], ventana[flujo])
        if trozo > 0:
            mandados += trozo
            ventana[0] -= trozo
            ventana[flujo] -= trozo
            c.mandar(trama(DATA, FIN_FLUJO if mandados == total else 0, flujo, b"x" * trozo))
        t = c.siguiente(time.monotonic() + (0.001 if trozo > 0 else 3))
        if t is None:
            if trozo == 0 and mandados == total:
                return mandados, None
            continue
        if t.tipo == WINDOW_UPDATE and t.flujo in ventana:
            ventana[t.flujo] += struct.unpack(">I", t.carga)[0] & 0x7FFFFFFF
        elif hasta_que(t):
            return mandados, t
    return mandados, None


def es_413_o_corte(flujo: int):
    def cumple(t: Trama) -> bool:
        if t.flujo != flujo:
            return t.tipo == GOAWAY
        if t.tipo == HEADERS:
            return estado_h2(t.carga) == "413"
        return t.tipo == RST_STREAM
    return cumple


def h2_055(puerto: int):
    c = H2(puerto)
    c.mandar(trama(HEADERS, FIN_CABECERAS, 1, post("/eco", CUERPO_DE_MAS)))
    anunciado = c.vigilar(3, lambda t: t.flujo == 1 or t.tipo == GOAWAY)
    if anunciado is None or anunciado.tipo != HEADERS or estado_h2(anunciado.carga) != "413":
        return False, f"content-length de 11 MiB sin mandar cuerpo: no responde 413 ({c.visto()})"
    corte = c.vigilar(3, lambda t: t.flujo == 1 and t.tipo == RST_STREAM)
    if corte is None or codigo_de(corte) != NO_ERROR:
        return False, f"tras el 413 anunciado falta RST_STREAM NO_ERROR: {c.visto()}"
    vistos = ["anunciado: 413 y RST NO_ERROR"]
    for flujo, camino in ((3, "/eco"), (5, "/")):
        c.mandar(trama(HEADERS, FIN_CABECERAS, flujo, post(camino)))
        mandados, fin = subir(c, flujo, CUERPO_DE_MAS, es_413_o_corte(flujo))
        if fin is None or fin.tipo == GOAWAY or (fin.tipo == RST_STREAM and codigo_de(fin) != NO_ERROR):
            return False, f"{camino} sin anunciar, {mandados >> 10} KiB: ni 413 ni RST NO_ERROR ({c.visto()})"
        vistos.append(f"{camino} sin anunciar: {'413' if fin.tipo == HEADERS else 'RST NO_ERROR'} a los {mandados >> 20} MiB")
    return True, "; ".join(vistos)


def descargar(c: H2, flujo: int) -> tuple[bool, int]:
    """Lee la respuesta de `flujo` dando crédito por cada trama; la conexión recupera también lo de otros flujos."""
    recibidos = 0
    limite = time.monotonic() + 60
    while time.monotonic() < limite and not c.cerrada:
        t = c.siguiente(limite)
        if t is None:
            break
        if t.tipo == GOAWAY or (t.tipo == RST_STREAM and t.flujo == flujo):
            return False, recibidos
        if t.tipo == DATA and t.carga:
            credito = struct.pack(">I", len(t.carga))
            c.mandar(trama(WINDOW_UPDATE, 0, 0, credito))
            if t.flujo == flujo:
                c.mandar(trama(WINDOW_UPDATE, 0, flujo, credito))
        if t.tipo == DATA and t.flujo == flujo:
            recibidos += len(t.carga)
            if t.banderas & FIN_FLUJO:
                return True, recibidos
    return False, recibidos


def descarga_legitima(puerto: int):
    c = H2(puerto)
    c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 1, get("/descarga")))
    termino, recibidos = descargar(c, 1)
    if not termino:
        return False, f"{recibidos >> 20} MiB de 32 y el servidor corta o no termina: {c.visto()}"
    return recibidos == 32 << 20, f"32 MiB con un WINDOW_UPDATE por trama: {recibidos >> 20} MiB recibidos"


def anular_y_volver(puerto: int):
    c = H2(puerto)
    c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 1, get("/descarga")))
    if c.vigilar(3, lambda t: t.tipo == DATA and t.flujo == 1 or t.tipo == GOAWAY) is None:
        return False, f"la primera descarga no empieza: {c.visto()}"
    c.mandar(trama(RST_STREAM, 0, 1, struct.pack(">I", CANCEL))
             + trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 3, get("/descarga")))
    termino, recibidos = descargar(c, 3)
    if not termino:
        return False, f"anulada la primera, la segunda corta a los {recibidos >> 20} MiB: {c.visto()}"
    return recibidos == 32 << 20, f"anula 32 MiB a medias y pide otros 32 por la misma conexión: {recibidos >> 20} MiB recibidos"


def leer_respuesta(s: socket.socket) -> tuple[bytes, bytes]:
    datos = b""
    while b"\r\n\r\n" not in datos:
        trozo = s.recv(4096)
        if not trozo:
            return datos, b""
        datos += trozo
    cabeza, cuerpo = datos.split(b"\r\n\r\n", 1)
    largo = next((int(l.split(b":")[1]) for l in cabeza.split(b"\r\n") if l.lower().startswith(b"content-length")), 0)
    while len(cuerpo) < largo:
        cuerpo += s.recv(4096)
    return cabeza, cuerpo


def http_024(puerto: int):
    with socket.create_connection(("127.0.0.1", puerto), timeout=5) as s:
        s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        cabeza, _ = leer_respuesta(s)
        if not cabeza.startswith(b"HTTP/1.1 200") or b"connection: close" in cabeza.lower():
            return False, f"la primera respuesta no deja la conexión abierta: {cabeza[:60]!r}"
        time.sleep(INACTIVIDAD / 2)
        s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        cabeza, _ = leer_respuesta(s)
        if not cabeza.startswith(b"HTTP/1.1 200"):
            return False, f"una segunda petición a media inactividad no se atiende: {cabeza[:60]!r}"
        empezo = time.monotonic()
        s.settimeout(INACTIVIDAD + 3 * PLAZO)
        try:
            cerro = s.recv(1) == b""
        except socket.timeout:
            cerro = False
        except OSError:
            cerro = True
        tardo = time.monotonic() - empezo
        ok = cerro and tardo >= 0.75 * INACTIVIDAD
        return ok, (f"dos peticiones por la misma conexión; {'cerró' if cerro else 'sigue abierta'} "
                    f"tras {tardo:.1f} s ociosa (inactividad {INACTIVIDAD:.0f} s)")


def http_026(puerto: int):
    with socket.create_connection(("127.0.0.1", puerto), timeout=5) as s:
        s.sendall(f"POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: {CUERPO_DE_MAS}\r\n\r\n".encode())
        try:
            cabeza, _ = leer_respuesta(s)
        except socket.timeout:
            return False, "anuncia 11 MiB y el servidor se queda esperándolos"
        linea = cabeza.split(b"\r\n")[0]
        return cabeza.startswith(b"HTTP/1.1 413"), f"anuncia 11 MiB: {linea!r}"


def escucha(puerto: int) -> bool:
    try:
        socket.create_connection(("127.0.0.1", puerto), timeout=0.5).close()
        return True
    except OSError:
        return False


def libre() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def http_025(orden: list[str]):
    vistos = []
    for malo in ("0", "-1"):
        puerto = libre()
        proceso = subprocess.Popen(orden + [str(puerto), malo], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL)
        try:
            codigo = proceso.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proceso.kill()
            proceso.wait()
            codigo = None
        if codigo in (None, 0) or escucha(puerto):
            return False, f"con un plazo de {malo} ms: {'sigue corriendo' if codigo is None else f'termina con {codigo}'}"
        vistos.append(f"{malo} → sale con {codigo}")
    return True, ", ".join(vistos)


VECTORES = [
    ("H2-050", "más de 256 campos, con cookies partidas", h2_050),
    ("H2-051", "el tope de lista anunciado es el que se aplica", h2_051),
    ("H2-052", "ventana a 0 y goteo de un octeto: el flujo se corta", h2_052),
    ("H2-053", "PING, WINDOW_UPDATE, SETTINGS y PRIORITY no reinician la inactividad", h2_053),
    ("H2-054", "presupuesto de respuestas en vuelo por conexión", h2_054),
    ("H2-055", "un cuerpo de más de 10 MiB: 413 o RST_STREAM NO_ERROR", h2_055),
    ("H2-038", "legítimo: una descarga de 32 MiB no es una inundación", descarga_legitima),
    ("H2-054", "legítimo: anular una descarga y pedir otra por la misma conexión", anular_y_volver),
    ("HTTP-024", "keep-alive ocioso: la conexión se cierra", http_024),
    ("HTTP-026", "un cuerpo de más de 10 MiB recibe 413", http_026),
]


def main() -> int:
    orden = sys.argv[1:]
    solo_http1 = orden[:1] == ["--solo-http1"]
    if solo_http1:
        orden = orden[1:]
    if not orden:
        print(__doc__)
        return 2
    puerto = libre()
    servidor = subprocess.Popen(orden + [str(puerto), str(int(PLAZO * 1000))],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    fallos = omitidos = 0
    try:
        hasta = time.monotonic() + 60
        while not escucha(puerto):
            if servidor.poll() is not None or time.monotonic() > hasta:
                print(f"el servidor no llegó a escuchar en :{puerto}")
                return 1
            time.sleep(0.2)

        for req, desc, vector in VECTORES:
            if solo_http1 and not req.startswith("HTTP-"):
                print(f"  n/a  {req:<9} {desc}")
                omitidos += 1
                continue
            try:
                ok, detalle = vector(puerto)
            except (OSError, ConnectionError, StopIteration) as fallo:
                ok, detalle = False, f"{fallo.__class__.__name__}: {fallo}"
            print(f'  {"ok  " if ok else "FALLA"} {req:<9} {desc}')
            print(f"        {detalle}")
            fallos += not ok
    finally:
        servidor.kill()
        servidor.wait()

    ok, detalle = http_025(orden)
    print(f'  {"ok  " if ok else "FALLA"} {"HTTP-025":<9} un plazo de 0 o negativo no arranca')
    print(f"        {detalle}")
    fallos += not ok

    total = len(VECTORES) + 1 - omitidos
    print(f"\n{total} vectores · {total - fallos} pasan · {fallos} fallan")
    if omitidos:
        print(f"{omitidos} de HTTP/2 no aplican: la implementación todavía no habla h2")
    return 1 if fallos else 0


if __name__ == "__main__":
    raise SystemExit(main())
