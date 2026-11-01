import errno
import io
import socket
import sys
import threading
import time
import traceback

from cero.peticion import (LIMITE_CUERPO, METODOS, Rechazo, encuadre, leer_cabecera,
                           leer_cuerpo)
from cero.respuesta import Respuesta, como_respuesta, serializar, texto
from cero.ruta import NoEncontrado, NoPermitido, Router
from cero.seguridad import Cabeceras, anadir
from cero.sesion import Memoria, cabecera_cookie

SIN_RECURSOS = {errno.EMFILE, errno.ENFILE, errno.ENOBUFS, errno.ENOMEM}


class _Plazo(io.RawIOBase):

    def __init__(self, conexion, espera):
        self.conexion = conexion
        self.espera = espera
        self.limite = time.monotonic() + espera

    def readable(self):
        return True

    def readinto(self, destino):
        resta = self.limite - time.monotonic()
        if resta <= 0:
            raise TimeoutError("plazo agotado")
        self.conexion.settimeout(min(resta, self.espera))
        return self.conexion.recv_into(destino)


class Servidor:

    def __init__(self, router=None, *, maximo_conexiones=256, espera=30, plazo_cabecera=10,
                 plazo_cuerpo=60, limite_cuerpo=LIMITE_CUERPO, sesiones=None,
                 cabeceras=Cabeceras(), cors=None, csrf=None, limitador=None):
        self.router = router or Router()
        self.sesiones = Memoria() if sesiones is None else sesiones
        self.cabeceras = cabeceras
        self.cors = cors
        self.csrf = csrf
        self.limitador = limitador
        self.espera = espera
        self.plazo_cabecera = plazo_cabecera
        self.plazo_cuerpo = plazo_cuerpo
        self.limite_cuerpo = limite_cuerpo
        self.listo = threading.Event()
        self.parado = threading.Event()
        self.puerto = None
        self.cupo = threading.BoundedSemaphore(maximo_conexiones)

    def responder(self, peticion):
        corte, extra = self._antes(peticion)
        respuesta = self._con_sesion(peticion) if corte is None else corte
        return self._blindar(anadir(respuesta, extra), peticion.segura)

    def _antes(self, peticion):
        corte, extra = None, []
        if self.limitador is not None:
            admitida, cabeceras = self.limitador.pedir(peticion.cliente)
            extra += cabeceras
            if not admitida:
                corte = texto(429, "demasiadas peticiones")
        if self.cors is not None:
            pedido = peticion.cabecera("access-control-request-method")
            estado, cabeceras = self.cors.decidir(peticion.verbo, peticion.cabecera("origin"), pedido)
            extra += cabeceras
            if corte is None and estado is not None:
                corte = Respuesta(estado)
        return corte, extra

    def _blindar(self, respuesta, segura=False):
        if self.cabeceras is None:
            return respuesta
        return anadir(respuesta, self.cabeceras.para(segura))

    def _con_sesion(self, peticion):
        peticion.almacen = self.sesiones
        try:
            respuesta = self._despachar(peticion)
        except Exception:
            traceback.print_exc(file=sys.stderr)
            return texto(500, f"error interno en {peticion.verbo} {peticion.camino}")
        sesion = peticion.abierta
        if sesion is None:
            return respuesta
        try:
            sesion.almacen.guardar(sesion)
        except Exception:
            traceback.print_exc(file=sys.stderr)
            return texto(500, "error interno al guardar la sesión")
        id = sesion.cookie_pendiente()
        if id is None:
            return respuesta
        galleta = ("Set-Cookie", cabecera_cookie(id, peticion.segura))
        return Respuesta(respuesta.estado, [*respuesta.cabeceras, galleta], respuesta.cuerpo)

    def _despachar(self, peticion):
        if peticion.destino == "*":
            return Respuesta(200, [("Allow", ", ".join(sorted(METODOS)))])
        resultado = self.router.resolver(peticion.verbo, peticion.camino)
        if isinstance(resultado, NoEncontrado):
            return texto(404, "no encontrado")
        if isinstance(resultado, NoPermitido):
            return texto(405, "método no permitido", [("Allow", ", ".join(resultado.verbos))])
        if self.csrf is not None and not self.csrf.admite(peticion):
            return texto(403, "token CSRF ausente o inválido")
        peticion.variables = resultado.variables
        return como_respuesta(resultado.accion(peticion))

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
                except OSError as fallo:
                    if fallo.errno in SIN_RECURSOS:
                        time.sleep(0.1)
                    continue
                self._repartir(conexion)

    def cerrar(self):
        self.parado.set()

    def _repartir(self, conexion):
        if not self.cupo.acquire(blocking=False):
            _rechazar(conexion, self._blindar(texto(503, "demasiadas conexiones")), 0.05)
            return
        try:
            threading.Thread(target=self._atender, args=(conexion,), daemon=True).start()
        except RuntimeError:
            self.cupo.release()
            conexion.close()

    def _atender(self, conexion):
        try:
            cliente = conexion.getpeername()[0]
            reloj = _Plazo(conexion, self.espera)
            with io.BufferedReader(reloj) as entrada:
                while self._una(conexion, reloj, entrada, cliente):
                    pass
        except OSError:
            pass
        finally:
            conexion.close()
            self.cupo.release()

    def _una(self, conexion, reloj, entrada, cliente):
        reloj.limite = time.monotonic() + self.plazo_cabecera
        try:
            peticion = leer_cabecera(entrada)
            if peticion is None:
                return False
            peticion.cliente = cliente
            forma = encuadre(peticion, self.limite_cuerpo)
            reloj.limite = time.monotonic() + self.plazo_cuerpo
            expect = peticion.cabecera("expect")
            if expect is not None:
                if expect.lower() != "100-continue":
                    raise Rechazo(417, "Expect no admitido")
                if peticion.version == "HTTP/1.1" and forma != 0:
                    conexion.settimeout(self.espera)
                    conexion.sendall(b"HTTP/1.1 100 Continue\r\n\r\n")
            leer_cuerpo(entrada, peticion, forma, self.limite_cuerpo)
        except Rechazo as rechazo:
            _rechazar(conexion, self._blindar(texto(rechazo.estado, rechazo.motivo)))
            return False
        except TimeoutError:
            _rechazar(conexion, self._blindar(texto(408, "la petición tardó demasiado")))
            return False
        seguir = peticion.persistente()
        modo = "close" if not seguir else "keep-alive" if peticion.version == "HTTP/1.0" else None
        try:
            datos = serializar(self.responder(peticion), peticion.verbo == "HEAD", modo)
        except ValueError:
            traceback.print_exc(file=sys.stderr)
            datos = serializar(self._blindar(texto(500, "error interno")), conexion="close")
            seguir = False
        conexion.settimeout(self.espera)
        conexion.sendall(datos)
        return seguir


def _rechazar(conexion, respuesta, espera=1):
    limite = time.monotonic() + espera
    try:
        conexion.settimeout(espera)
        conexion.sendall(serializar(respuesta, conexion="close"))
        conexion.shutdown(socket.SHUT_WR)
        while (resta := limite - time.monotonic()) > 0:
            conexion.settimeout(resta)
            if not conexion.recv(65536):
                break
    except OSError:
        pass
    finally:
        conexion.close()
