import contextlib
import errno
import socket
import sys
import threading
import traceback

from cero.peticion import METODOS, Rechazo, encuadre, leer_cabecera, leer_cuerpo
from cero.respuesta import Respuesta, como_respuesta, serializar, texto
from cero.ruta import NoEncontrado, NoPermitido, Router

ESPERA = 30
MAXIMO_CONEXIONES = 512


class Servidor:

    def __init__(self, router=None):
        self.router = router or Router()
        self.listo = threading.Event()
        self.parado = threading.Event()
        self.puerto = None
        self.cupo = threading.BoundedSemaphore(MAXIMO_CONEXIONES)

    def responder(self, peticion):
        if peticion.destino == "*":
            return Respuesta(200, [("Allow", ", ".join(sorted(METODOS)))])
        resultado = self.router.resolver(peticion.verbo, peticion.camino)
        if isinstance(resultado, NoEncontrado):
            return texto(404, "no encontrado")
        if isinstance(resultado, NoPermitido):
            return texto(405, "método no permitido", [("Allow", ", ".join(resultado.verbos))])
        peticion.variables = resultado.variables
        try:
            return como_respuesta(resultado.accion(peticion))
        except Exception:
            traceback.print_exc(file=sys.stderr)
            return texto(500, f"error interno en {peticion.verbo} {peticion.camino}")

    def escuchar(self, puerto, anfitrion="127.0.0.1"):
        try:
            oyente = socket.create_server((anfitrion, puerto))
        except OSError as fallo:
            if fallo.errno == errno.EADDRINUSE:
                raise OSError(fallo.errno, f"no se puede escuchar en {anfitrion}:{puerto}: "
                                           "el puerto está ocupado") from fallo
            raise
        with oyente:
            oyente.settimeout(0.2)
            self.puerto = oyente.getsockname()[1]
            self.listo.set()
            while not self.parado.is_set():
                try:
                    conexion, _ = oyente.accept()
                except TimeoutError:
                    continue
                if not self.cupo.acquire(blocking=False):
                    with conexion, contextlib.suppress(OSError):
                        conexion.sendall(serializar(texto(503, "demasiadas conexiones"), cerrar=True))
                    continue
                threading.Thread(target=self._atender, args=(conexion,), daemon=True).start()

    def cerrar(self):
        self.parado.set()

    def _atender(self, conexion):
        try:
            conexion.settimeout(ESPERA)
            with conexion.makefile("rb") as entrada:
                while self._una(conexion, entrada):
                    pass
        except OSError:
            pass
        finally:
            conexion.close()
            self.cupo.release()

    def _una(self, conexion, entrada):
        try:
            peticion = leer_cabecera(entrada)
            if peticion is None:
                return False
            forma = encuadre(peticion)
            expect = peticion.cabecera("expect")
            if expect is not None:
                if expect.lower() != "100-continue":
                    raise Rechazo(417, "Expect no admitido")
                if peticion.version == "HTTP/1.1" and forma != 0:
                    conexion.sendall(b"HTTP/1.1 100 Continue\r\n\r\n")
            leer_cuerpo(entrada, peticion, forma)
        except Rechazo as rechazo:
            _rechazar(conexion, texto(rechazo.estado, rechazo.motivo))
            return False
        seguir = peticion.persistente()
        respuesta = self.responder(peticion)
        conexion.sendall(serializar(respuesta, peticion.verbo == "HEAD", not seguir))
        return seguir


def _rechazar(conexion, respuesta):
    try:
        conexion.settimeout(1)
        conexion.sendall(serializar(respuesta, cerrar=True))
        conexion.shutdown(socket.SHUT_WR)
        for _ in range(64):
            if not conexion.recv(65536):
                break
    except OSError:
        pass
    finally:
        conexion.close()
