import pathlib
import socket
import subprocess
import sys
import threading
import time
import unittest

from cero.peticion import LIMITE_CUERPO
from cero.ruta import Router
from cero.servidor import Servidor

from pruebas.test_hostil import puerto_libre
from pruebas.test_servidor import leer_respuesta, pedir

PYTHON = pathlib.Path(__file__).resolve().parent.parent
GET = b"GET / HTTP/1.1\r\nHost: x\r\n\r\n"
AJUSTES = ("maximo_conexiones", "espera", "plazo_cabecera", "plazo_cuerpo", "inactividad",
           "limite_cuerpo")


class Contador:

    def __init__(self):
        self.llamadas = 0

    def __call__(self, peticion):
        self.llamadas += 1
        return peticion.cuerpo


def arrancar(prueba, **ajustes):
    contador = Contador()
    router = Router()
    router.agregar("GET", "/", lambda peticion: "ok")
    router.agregar("POST", "/eco", contador)
    servidor = Servidor(router, **ajustes)
    hilo = threading.Thread(target=servidor.escuchar, args=(0,), daemon=True)
    hilo.start()
    servidor.listo.wait(5)
    prueba.addCleanup(hilo.join, 5)
    prueba.addCleanup(servidor.cerrar)
    return servidor, contador


def esperar_cierre(conexion, maximo):
    inicio = time.monotonic()
    conexion.settimeout(maximo)
    try:
        resto = conexion.recv(100)
    except TimeoutError:
        return None, time.monotonic() - inicio
    except ConnectionResetError:
        resto = b""
    return resto, time.monotonic() - inicio


def conforme(prueba, *argumentos):
    proceso = subprocess.Popen([sys.executable, "-B", "-m", "cero.conforme", *argumentos],
                               cwd=PYTHON, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    prueba.addCleanup(proceso.stderr.close)
    prueba.addCleanup(proceso.wait, 5)
    prueba.addCleanup(proceso.kill)
    return proceso


def escucha(puerto):
    try:
        socket.create_connection(("127.0.0.1", puerto), timeout=0.5).close()
        return True
    except OSError:
        return False


class Inactividad(unittest.TestCase):

    def test_http_024_keep_alive_ocioso_se_cierra_sin_responder(self):
        servidor, _ = arrancar(self, inactividad=0.6)
        with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=5) as conexion:
            conexion.sendall(GET)
            with conexion.makefile("rb") as archivo:
                estado, cabeceras, largo = leer_respuesta(archivo)
                self.assertEqual((estado, archivo.read(largo)), (200, b"ok"))
                self.assertNotEqual(cabeceras.get("connection"), "close")
            resto, tardo = esperar_cierre(conexion, 5)
        self.assertEqual(resto, b"", "ni se cerró ni debía responder nada")
        self.assertGreaterEqual(tardo, 0.45)
        self.assertLess(tardo, 2.5)

    def test_http_024_la_inactividad_no_la_acorta_la_espera_de_cada_lectura(self):
        servidor, _ = arrancar(self, espera=0.3, inactividad=1.5)
        with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=5) as conexion:
            with conexion.makefile("rb") as archivo:
                for pausa in (0, 0.8):
                    time.sleep(pausa)
                    conexion.sendall(GET)
                    estado, _, largo = leer_respuesta(archivo)
                    self.assertEqual((estado, archivo.read(largo)), (200, b"ok"))

    def test_http_024_el_servidor_del_banco_toma_el_plazo_en_milisegundos(self):
        puerto = puerto_libre()
        proceso = conforme(self, str(puerto), "300")
        limite = time.monotonic() + 10
        while not escucha(puerto):
            self.assertIsNone(proceso.poll())
            self.assertLess(time.monotonic(), limite)
            time.sleep(0.05)
        with socket.create_connection(("127.0.0.1", puerto), timeout=5) as conexion:
            conexion.sendall(GET)
            with conexion.makefile("rb") as archivo:
                estado, _, largo = leer_respuesta(archivo)
                archivo.read(largo)
            self.assertEqual(estado, 200)
            resto, tardo = esperar_cierre(conexion, 5)
        self.assertEqual(resto, b"")
        self.assertGreaterEqual(tardo, 0.45)
        self.assertLess(tardo, 2.5)


class AjustesInvalidos(unittest.TestCase):

    def test_http_025_cero_o_negativo_no_construye_el_servidor(self):
        for ajuste in AJUSTES:
            for malo in (0, -1, -0.5):
                with self.subTest(ajuste=ajuste, valor=malo):
                    with self.assertRaises(ValueError) as fallo:
                        Servidor(Router(), **{ajuste: malo})
                    self.assertIn(ajuste, str(fallo.exception))

    def test_http_025_los_valores_validos_siguen_construyendo(self):
        Servidor(Router(), maximo_conexiones=1, espera=0.01, plazo_cabecera=0.01,
                 plazo_cuerpo=0.01, inactividad=0.01, limite_cuerpo=1)

    def test_http_025_el_servidor_del_banco_no_llega_a_escuchar(self):
        for malo in ("0", "-1"):
            with self.subTest(plazo=malo):
                puerto = puerto_libre()
                proceso = conforme(self, str(puerto), malo)
                codigo = proceso.wait(10)
                self.assertNotEqual(codigo, 0)
                self.assertFalse(escucha(puerto))
                self.assertIn(b"plazo", proceso.stderr.read())


class CuerpoDeMas(unittest.TestCase):

    def assert_413_y_cierre(self, crudo, **ajustes):
        servidor, contador = arrancar(self, **ajustes)
        with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=5) as conexion:
            conexion.sendall(crudo)
            with conexion.makefile("rb") as archivo:
                estado, cabeceras, largo = leer_respuesta(archivo)
                archivo.read(largo)
            resto, _ = esperar_cierre(conexion, 3)
        self.assertEqual(estado, 413)
        self.assertEqual(cabeceras.get("connection"), "close")
        self.assertEqual(resto, b"")
        self.assertEqual(contador.llamadas, 0, "la acción corrió con un cuerpo de más")

    def test_http_026_content_length_de_mas_es_413_sin_leer_el_cuerpo(self):
        self.assert_413_y_cierre(b"POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: 11\r\n\r\n",
                                 limite_cuerpo=10)

    def test_http_026_trozos_que_pasan_del_tope_son_413_y_cierre(self):
        self.assert_413_y_cierre(b"POST /eco HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                                 b"6\r\n012345\r\n6\r\n012345\r\n0\r\n\r\n", limite_cuerpo=10)

    def test_http_026_el_tope_por_defecto_es_10_mib(self):
        self.assertEqual(LIMITE_CUERPO, 10 * 1024 * 1024)
        self.assertEqual(Servidor(Router()).limite_cuerpo, LIMITE_CUERPO)
        self.assert_413_y_cierre(b"POST /eco HTTP/1.1\r\nHost: x\r\n"
                                 b"Content-Length: 10485761\r\n\r\n")

    def test_http_026_el_tope_justo_se_admite(self):
        servidor, contador = arrancar(self, limite_cuerpo=10)
        estado, _, cuerpo = pedir(servidor.puerto, b"POST /eco HTTP/1.1\r\nHost: x\r\n"
                                                   b"Content-Length: 10\r\n\r\n0123456789")
        self.assertEqual((estado, cuerpo, contador.llamadas), (200, b"0123456789", 1))
