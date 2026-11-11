import re
from dataclasses import dataclass, field

from cero.seguridad import token_csrf
from cero.sesion import id_de_cookie

METODOS = {"GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE"}
LIMITE_LINEA = 8192
LIMITE_CABECERAS = 100
LIMITE_CUERPO = 10 * 1024 * 1024
PORCION = 64 * 1024

TOKEN = re.compile(r"[!#$%&'*+\-.^_`|~0-9A-Za-z]+")
VERSION = re.compile(r"HTTP/[0-9]\.[0-9]")
DIGITOS = re.compile(r"[0-9]{1,15}")
HEXADECIMAL = re.compile(r"[0-9A-Fa-f]{1,8}")
CONTROL = re.compile(r"[\x00-\x08\x0a-\x1f\x7f]")
DESTINO = re.compile(r"[\x21-\x7e]+")


class Rechazo(Exception):

    def __init__(self, estado, motivo):
        super().__init__(motivo)
        self.estado = estado
        self.motivo = motivo


@dataclass
class Peticion:
    verbo: str
    destino: str
    camino: str
    consulta: str
    version: str
    cabeceras: list
    cuerpo: bytearray = field(default_factory=bytearray)
    variables: dict = field(default_factory=dict)
    segura: bool = False
    cliente: str = ""
    datos: object = None
    patron: str = None
    usuario: str = None
    almacen: object = field(default=None, repr=False)
    abierta: object = field(default=None, repr=False)
    buscada: bool = field(default=False, repr=False)

    def cabecera(self, nombre):
        valores = self.todas(nombre)
        return ", ".join(valores) if valores else None

    def todas(self, nombre):
        nombre = nombre.lower()
        return [v for n, v in self.cabeceras if n == nombre]

    def sesion(self):
        if self.buscada or self.almacen is None:
            return self.abierta
        self.buscada = True
        self.abierta = self.almacen.recuperar(id_de_cookie(self.todas("cookie")))
        return self.abierta

    def abrir_sesion(self):
        actual = self.sesion()
        if actual is not None and actual.viva:
            return actual
        if self.almacen is None:
            raise RuntimeError("esta petición no tiene almacén de sesiones")
        self.abierta = self.almacen.crear()
        return self.abierta

    def token_csrf(self):
        return token_csrf(self.abrir_sesion())

    def persistente(self):
        conexion = {t.strip().lower() for t in (self.cabecera("connection") or "").split(",")}
        if self.version == "HTTP/1.1":
            return "close" not in conexion
        return "keep-alive" in conexion


def _linea(entrada, estado_si_largo=431):
    linea = entrada.readline(LIMITE_LINEA + 1)
    if not linea:
        return None
    if len(linea) > LIMITE_LINEA:
        raise Rechazo(estado_si_largo, "línea demasiado larga")
    if not linea.endswith(b"\r\n"):
        raise Rechazo(400, "la línea no termina en CRLF")
    return linea[:-2].decode("latin-1")


def _destino(verbo, destino):
    if destino == "*":
        if verbo != "OPTIONS":
            raise Rechazo(400, "el destino * solo vale para OPTIONS")
        return "*", ""
    if not DESTINO.fullmatch(destino):
        raise Rechazo(400, "destino con caracteres no admitidos")
    if destino.lower().startswith(("http://", "https://")):
        resto = destino.split("://", 1)[1]
        destino = "/" + resto.split("/", 1)[1] if "/" in resto else "/"
    if not destino.startswith("/"):
        raise Rechazo(400, "destino mal formado")
    camino, _, consulta = destino.partition("?")
    return camino.partition("#")[0], consulta


def leer_cabecera(entrada):
    linea = _linea(entrada)
    if linea is None:
        return None
    partes = linea.split(" ")
    if len(partes) != 3 or not all(partes):
        raise Rechazo(400, "línea de petición mal formada")
    verbo, destino, version = partes
    if not TOKEN.fullmatch(verbo):
        raise Rechazo(400, "método mal formado")
    if not VERSION.fullmatch(version):
        raise Rechazo(400, "versión mal formada")
    if version not in ("HTTP/1.1", "HTTP/1.0"):
        raise Rechazo(505, "versión no admitida")
    if verbo not in METODOS:
        raise Rechazo(501, "método no implementado")
    camino, consulta = _destino(verbo, destino)

    cabeceras = []
    while True:
        linea = _linea(entrada)
        if linea is None:
            raise Rechazo(400, "la conexión se cerró en las cabeceras")
        if linea == "":
            break
        if len(cabeceras) == LIMITE_CABECERAS:
            raise Rechazo(431, "demasiadas cabeceras")
        if linea[0] in " \t":
            raise Rechazo(400, "cabecera plegada")
        nombre, dos_puntos, valor = linea.partition(":")
        if not dos_puntos or not TOKEN.fullmatch(nombre):
            raise Rechazo(400, "nombre de cabecera mal formado")
        valor = valor.strip(" \t")
        if CONTROL.search(valor):
            raise Rechazo(400, "valor de cabecera con caracteres de control")
        cabeceras.append((nombre.lower(), valor))

    peticion = Peticion(verbo, destino, camino, consulta, version, cabeceras)
    hosts = peticion.todas("host")
    if len(hosts) > 1 or (version == "HTTP/1.1" and not hosts):
        raise Rechazo(400, "Host ausente o duplicado")
    return peticion


def _longitud(peticion, limite):
    valores = peticion.todas("content-length")
    if not valores:
        return 0
    elementos = {v.strip(" \t") for v in ",".join(valores).split(",")}
    if len(elementos) != 1 or not DIGITOS.fullmatch(next(iter(elementos))):
        raise Rechazo(400, "Content-Length inválido")
    longitud = int(elementos.pop())
    if longitud > limite:
        raise Rechazo(413, "cuerpo demasiado grande")
    return longitud


def encuadre(peticion, limite=LIMITE_CUERPO):
    if not peticion.todas("transfer-encoding"):
        return _longitud(peticion, limite)
    if peticion.todas("content-length"):
        raise Rechazo(400, "Content-Length y Transfer-Encoding juntos")
    lista = [c.strip().lower() for c in peticion.cabecera("transfer-encoding").split(",")]
    if lista[-1] != "chunked" or lista.count("chunked") != 1:
        raise Rechazo(400, "chunked debe ser la última codificación")
    if lista != ["chunked"]:
        raise Rechazo(501, "codificación de transferencia no implementada")
    return "chunked"


def _anadir(entrada, destino, n):
    while n:
        trozo = entrada.read(min(n, PORCION))
        if not trozo:
            raise Rechazo(400, "el cuerpo es más corto de lo anunciado")
        destino += trozo
        n -= len(trozo)


def leer_cuerpo(entrada, peticion, forma, limite=LIMITE_CUERPO):
    cuerpo = bytearray()
    if forma != "chunked":
        _anadir(entrada, cuerpo, forma)
        peticion.cuerpo = cuerpo
        return
    while True:
        linea = _linea(entrada, 400)
        if linea is None:
            raise Rechazo(400, "la conexión se cerró en un trozo")
        tamano, _, extension = linea.partition(";")
        tamano = tamano.rstrip(" \t")
        if not HEXADECIMAL.fullmatch(tamano) or CONTROL.search(extension):
            raise Rechazo(400, "línea de trozo mal formada")
        tamano = int(tamano, 16)
        if len(cuerpo) + tamano > limite:
            raise Rechazo(413, "cuerpo demasiado grande")
        if tamano == 0:
            break
        _anadir(entrada, cuerpo, tamano)
        if entrada.read(2) != b"\r\n":
            raise Rechazo(400, "trozo sin CRLF al final")
    for _ in range(LIMITE_CABECERAS + 1):
        linea = _linea(entrada, 400)
        if linea is None:
            raise Rechazo(400, "la conexión se cerró en los trailers")
        if linea == "":
            peticion.cuerpo = cuerpo
            return
    raise Rechazo(431, "demasiados trailers")
