#!/usr/bin/env python3
"""Corre los vectores de conformidad contra cualquier servidor, en cualquier lenguaje.

Los vectores son bytes sobre un socket: no saben quién responde. Eso es lo que permite que el
mismo juez valga para la implementación en Java, la de Rust y las que vengan.

    python3 spec/banco/correr.py 127.0.0.1 8777
"""

import json
import pathlib
import socket
import sys

AQUI = pathlib.Path(__file__).resolve().parent


def estado(host: str, puerto: int, crudo: str, espera_cuerpo: bool) -> int | str:
    """Manda los octetos tal cual y devuelve el código de la línea de estado."""
    peticion = crudo.encode("latin-1", "strict").decode("unicode_escape").encode("latin-1")
    try:
        with socket.create_connection((host, puerto), timeout=5) as s:
            s.sendall(peticion)
            datos = b""
            while b"\r\n" not in datos and len(datos) < 4096:
                trozo = s.recv(4096)
                if not trozo:
                    break
                datos += trozo
    except OSError as fallo:
        return f"sin respuesta ({fallo.__class__.__name__})"
    if not datos:
        # Cerrar sin responder es una respuesta: el servidor decidió no hablar.
        return "conexión cerrada sin línea de estado"
    linea = datos.split(b"\r\n", 1)[0].decode("latin-1", "replace")
    partes = linea.split(" ")
    if len(partes) < 2 or not partes[1].isdigit():
        return f"línea de estado ilegible: {linea!r}"
    return int(partes[1])


def main() -> int:
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    puerto = int(sys.argv[2]) if len(sys.argv) > 2 else 8777
    vectores = json.loads((AQUI / "conformidad-http1.json").read_text())

    fallos = []
    for v in vectores:
        visto = estado(host, puerto, v["peticion"], True)
        ok = visto == v["esperado"]
        marca = "ok  " if ok else "FALLA"
        print(f'  {marca} {v["apartado"]:<12} {v["desc"]}')
        if not ok:
            print(f'        esperado {v["esperado"]}, visto {visto}')
            fallos.append(v)

    print(f'\n{len(vectores)} vectores · {len(vectores) - len(fallos)} pasan · {len(fallos)} fallan')
    return 1 if fallos else 0


if __name__ == "__main__":
    raise SystemExit(main())
