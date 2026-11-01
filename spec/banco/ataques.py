#!/usr/bin/env python3
"""Los ataques de HTTP/2 Bomb y los plazos, en bytes crudos, contra cualquier implementación.

A diferencia de correr.py, este arranca el servidor él mismo, porque necesita plazos cortos y
porque uno de los requisitos es justo que el servidor se niegue a arrancar:

    python3 spec/banco/ataques.py ./c++/build/conforme
    python3 spec/banco/ataques.py ./rust/target/release/conforme
    python3 spec/banco/ataques.py java -cp <clases> cero.http.Conforme

A la orden se le añaden dos argumentos, `<puerto> <plazo en ms>`, y el servidor de conformidad
tiene que usar ese plazo para esperar crédito y para escribir, y el doble para la inactividad.
"""

import socket
import struct
import subprocess
import sys
import time
from collections import namedtuple

PLAZO = 1.0

PREAMBULO = b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
DATA, HEADERS, RST_STREAM, SETTINGS, PING, GOAWAY, WINDOW_UPDATE = 0x0, 0x1, 0x3, 0x4, 0x6, 0x7, 0x8
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


def h2_050(puerto: int):
    c = H2(puerto)
    bloque = GET_RAIZ + a_la_tabla("cookie", "a=1") + REPITE_LA_ULTIMA * 299
    c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, 1, bloque))
    ok = c.vigilar(3, goaway(ENHANCE_YOUR_CALM)) is not None
    return ok, f"303 campos en {len(bloque)} octetos: {c.visto()}"


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
    goteo = trama(WINDOW_UPDATE, 0, 1, struct.pack(">I", 1))
    fin = c.vigilar(6 * PLAZO, lambda t: t.flujo == 1 and t.tipo in (RST_STREAM, DATA) and
                    (t.tipo == RST_STREAM or t.banderas & FIN_FLUJO), PLAZO / 4, goteo)
    ok = fin is not None and fin.tipo == RST_STREAM and codigo_de(fin) == CANCEL
    return ok, f"un octeto de crédito cada {PLAZO / 4:.2f} s: {c.visto()}"


def h2_053(puerto: int):
    c = H2(puerto)
    ping = trama(PING, 0, 0, b"latido!!")
    empezo = time.monotonic()
    ok = c.vigilar(5 * PLAZO, goaway(NO_ERROR), PLAZO / 2, ping) is not None
    return ok, f"un PING cada {PLAZO / 2:.1f} s, {time.monotonic() - empezo:.1f} s: {c.visto()}"


def h2_054(puerto: int):
    c = H2(puerto, struct.pack(">HI", VENTANA_INICIAL, 0))
    for flujo in range(1, 80, 2):
        c.mandar(trama(HEADERS, FIN_FLUJO | FIN_CABECERAS, flujo, get("/grande")))
    ok = c.vigilar(3 * PLAZO, goaway(ENHANCE_YOUR_CALM)) is not None
    return ok, f"40 respuestas de 1 MiB con la ventana a 0: {c.visto()}"


def http_024(puerto: int):
    with socket.create_connection(("127.0.0.1", puerto), timeout=5) as s:
        s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        datos = b""
        while b"\r\n\r\n" not in datos:
            datos += s.recv(4096)
        cabeza, cuerpo = datos.split(b"\r\n\r\n", 1)
        largo = next(int(l.split(b":")[1]) for l in cabeza.split(b"\r\n") if l.lower().startswith(b"content-length"))
        while len(cuerpo) < largo:
            cuerpo += s.recv(4096)
        empezo = time.monotonic()
        s.settimeout(5 * PLAZO)
        try:
            cerro = s.recv(1) == b""
        except socket.timeout:
            cerro = False
        except OSError:
            cerro = True
        return cerro, f"{'cerró' if cerro else 'sigue abierta'} tras {time.monotonic() - empezo:.1f} s ociosa"


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
    ("H2-053", "los PING no reinician la inactividad", h2_053),
    ("H2-054", "presupuesto de respuestas en vuelo por conexión", h2_054),
    ("HTTP-024", "keep-alive ocioso: la conexión se cierra", http_024),
]


def main() -> int:
    orden = sys.argv[1:]
    if not orden:
        print(__doc__)
        return 2
    puerto = libre()
    servidor = subprocess.Popen(orden + [str(puerto), str(int(PLAZO * 1000))],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    fallos = 0
    try:
        hasta = time.monotonic() + 60
        while not escucha(puerto):
            if servidor.poll() is not None or time.monotonic() > hasta:
                print(f"el servidor no llegó a escuchar en :{puerto}")
                return 1
            time.sleep(0.2)

        for req, desc, vector in VECTORES:
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

    total = len(VECTORES) + 1
    print(f"\n{total} vectores · {total - fallos} pasan · {fallos} fallan")
    return 1 if fallos else 0


if __name__ == "__main__":
    raise SystemExit(main())
