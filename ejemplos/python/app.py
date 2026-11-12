import os

from cero.respuesta import en_json
from cero.ruta import Router
from cero.servidor import Servidor


def main():
    router = Router()
    router.get("/", lambda p: {"hola": "mundo"})
    router.get("/saludo/{nombre}", lambda p: {"saludo": "hola, " + p.variables["nombre"]})
    router.post("/eco", lambda p: en_json(p.json()))
    Servidor(router).escuchar(int(os.environ.get("PUERTO", 8080)))


if __name__ == "__main__":
    main()
