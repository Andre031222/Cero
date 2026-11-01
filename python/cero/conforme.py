import sys

from cero.ruta import Router
from cero.servidor import Servidor


def eco(peticion):
    return peticion.cuerpo


def main():
    router = Router()
    router.agregar("GET", "/", lambda peticion: "ok")
    router.agregar("GET", "/eco", eco)
    router.agregar("POST", "/eco", eco)
    puerto = int(sys.argv[1]) if len(sys.argv) > 1 else 8777
    print(f"cero escucha en 127.0.0.1:{puerto}", flush=True)
    Servidor(router).escuchar(puerto)


if __name__ == "__main__":
    main()
