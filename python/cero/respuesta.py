import json
from dataclasses import dataclass, field
from email.utils import formatdate
from http import HTTPStatus


@dataclass
class Respuesta:
    estado: int
    cabeceras: list = field(default_factory=list)
    cuerpo: bytes = b""


def texto(estado, contenido, cabeceras=()):
    return Respuesta(estado, [("Content-Type", "text/plain; charset=utf-8"), *cabeceras],
                     contenido.encode())


def en_json(valor, estado=200):
    cuerpo = json.dumps(valor, ensure_ascii=False, separators=(",", ":")).encode()
    return Respuesta(estado, [("Content-Type", "application/json")], cuerpo)


def como_respuesta(valor):
    if isinstance(valor, Respuesta):
        return valor
    if valor is None:
        return Respuesta(204)
    if isinstance(valor, str):
        return texto(200, valor)
    if isinstance(valor, (bytes, bytearray)):
        return Respuesta(200, [("Content-Type", "application/octet-stream")], bytes(valor))
    if isinstance(valor, (dict, list)):
        return en_json(valor)
    raise TypeError(f"una acción no puede devolver {type(valor).__name__}")


ENCUADRE = {"content-length", "transfer-encoding", "connection"}


def serializar(respuesta, sin_cuerpo=False, conexion=None):
    try:
        frase = HTTPStatus(respuesta.estado).phrase
    except ValueError:
        frase = ""
    lineas = [f"HTTP/1.1 {respuesta.estado} {frase}", f"Date: {formatdate(usegmt=True)}"]
    for nombre, valor in respuesta.cabeceras:
        if any(c in f"{nombre}{valor}" for c in "\r\n\0"):
            raise ValueError(f"cabecera de respuesta con un salto de línea: {nombre!r}")
        if nombre.lower() not in ENCUADRE:
            lineas.append(f"{nombre}: {valor}")
    lleva_cuerpo = respuesta.estado not in (204, 304)
    if lleva_cuerpo:
        lineas.append(f"Content-Length: {len(respuesta.cuerpo)}")
    if conexion:
        lineas.append(f"Connection: {conexion}")
    cabeza = ("\r\n".join(lineas) + "\r\n\r\n").encode("latin-1")
    return cabeza + respuesta.cuerpo if lleva_cuerpo and not sin_cuerpo else cabeza
