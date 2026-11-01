import os
import sys
import time
from urllib.parse import parse_qs

from cero.observabilidad import Acceso, Metricas, Registro, Salud, interpolar
from cero.respuesta import Respuesta
from cero.ruta import Router
from cero.servidor import Servidor

IGNORADAS = ["/salud", "/metricas", "/control"]


def eco(peticion):
    return peticion.cuerpo


def consulta(peticion, nombre, defecto=""):
    return parse_qs(peticion.consulta).get(nombre, [defecto])[0]


class Interruptor:

    def __init__(self, nombre):
        self.nombre = nombre
        self.estado = "bien"

    def __call__(self):
        if self.estado == "lanza":
            raise ConnectionError(f"la comprobación {self.nombre} no responde")
        return self.estado == "bien"


def plazos(argumentos):
    if not argumentos:
        return {}
    plazo = int(argumentos[0]) / 1000
    return {"espera": plazo, "inactividad": 2 * plazo}


def fallar(peticion):
    raise RuntimeError("SECRETO-7f3")


def main():
    entorno = os.environ
    registro = Registro(entorno.get("CERO_CONF_LOG") or None, entorno.get("CERO_CONF_NIVEL") or "INFO")
    salud = Salud(publico=entorno.get("CERO_CONF_SALUD_PUBLICA") == "1")
    interruptores = {nombre: Interruptor(nombre) for nombre in ("uno", "dos")}
    for nombre, interruptor in interruptores.items():
        salud.comprobacion(nombre, interruptor)
    metricas = Metricas(ignoradas=IGNORADAS)

    def control(peticion):
        interruptores[peticion.variables["n"]].estado = consulta(peticion, "estado", "bien")

    def registrar(peticion):
        registro.escribir(consulta(peticion, "nivel", "INFO"), "conforme", "valor={} y {}",
                          consulta(peticion, "valor"))

    def interpolado(peticion):
        return interpolar("a={} b={}", 1) if consulta(peticion, "caso") == "menos" \
            else interpolar("a={}", 1, 2)

    router = Router()
    router.agregar("GET", "/", lambda peticion: "ok")
    router.agregar("GET", "/eco", eco)
    router.agregar("POST", "/eco", eco)
    router.agregar("GET", "/texto", lambda peticion: "texto")
    router.agregar("GET", "/lento", lambda peticion: time.sleep(
        min(int(consulta(peticion, "ms", "0") or 0), 10_000) / 1000))
    router.agregar("GET", "/rut/a/{x}", lambda peticion: "x=" + peticion.variables["x"])
    router.agregar("GET", "/falla/excepcion", fallar)
    router.agregar("GET", "/falla/vista", lambda peticion: Respuesta(200, [("X-Vista", "\n")]))
    router.agregar("GET", "/salud/vida", salud.vivo)
    router.agregar("GET", "/salud/lista", salud.listo)
    router.agregar("POST", "/control/salud/{n}", control)
    router.agregar("GET", "/metricas", metricas.exponer)
    router.agregar("GET", "/registrar", registrar)
    router.agregar("GET", "/registrar/interpolar", interpolado)
    puerto = int(sys.argv[1]) if len(sys.argv) > 1 else 8777
    try:
        servidor = Servidor(router, registro=registro, metricas=metricas,
                            acceso=Acceso(entorno.get("CERO_CONF_ACCESO") or None, ignoradas=IGNORADAS),
                            **plazos(sys.argv[2:3]))
    except ValueError as fallo:
        print(f"cero no arranca: plazo inválido: {fallo}", file=sys.stderr, flush=True)
        raise SystemExit(2)
    print(f"cero escucha en 127.0.0.1:{puerto}", file=sys.stderr, flush=True)
    servidor.escuchar(puerto)


if __name__ == "__main__":
    main()
