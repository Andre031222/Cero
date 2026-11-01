import io
import pathlib
import resource
import select
import socket
import subprocess
import sys
import threading
import time
import unittest
from unittest import mock

import cero
from cero.peticion import Rechazo, encuadre, leer_cabecera, leer_cuerpo
from cero.respuesta import Respuesta, serializar
from cero.ruta import Router
from cero.servidor import Servidor

from pruebas.test_servidor import arrancar, leer_respuesta, pedir

PYTHON = pathlib.Path(__file__).resolve().parent.parent
GET = b"GET / HTTP/1.1\r\nHost: x\r\n\r\n"


def leer(crudo, limite=1024 * 1024):
    entrada = io.BufferedReader(io.BytesIO(crudo))
    peticion = leer_cabecera(entrada)
    leer_cuerpo(entrada, peticion, encuadre(peticion, limite), limite)
    return peticion


def puerto_libre():
    with socket.create_server(("127.0.0.1", 0)) as oyente:
        return oyente.getsockname()[1]


def gotear(puerto, cabeza, cada, hasta):
    inicio = time.monotonic()
    with socket.create_connection(("127.0.0.1", puerto), timeout=5) as conexion:
        conexion.sendall(cabeza)
        while time.monotonic() - inicio < hasta:
            if select.select([conexion], [], [], cada)[0]:
                return time.monotonic() - inicio, conexion.recv(100)
            try:
                conexion.sendall(b"a")
            except OSError:
                return time.monotonic() - inicio, b""
    return time.monotonic() - inicio, None


class Encuadre(unittest.TestCase):

    def rechaza(self, crudo, estado=400):
        with self.assertRaises(Rechazo) as fallo:
            leer(crudo)
        self.assertEqual(fallo.exception.estado, estado)

    def test_http_019_content_length_vacio_se_rechaza(self):
        self.rechaza(b"POST / HTTP/1.1\r\nHost: x\r\nContent-Length:\r\n\r\nGET / HTTP/1.1\r\n")
        self.rechaza(b"POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 2,\r\n\r\nab")
        self.rechaza(b"POST / HTTP/1.1\r\nHost: x\r\nContent-Length: +2\r\n\r\nab")

    def test_http_017_content_length_repetido_e_igual_se_admite(self):
        crudo = b"POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nab"
        self.assertEqual(leer(crudo).cuerpo, b"ab")

    def test_http_021_tamano_de_trozo_con_espacio_delante_se_rechaza(self):
        self.rechaza(b"POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n 3\r\nabc\r\n0\r\n\r\n")

    def test_http_021_cr_suelto_en_la_extension_se_rechaza(self):
        self.rechaza(b"POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                     b"3;a\rb\r\nabc\r\n0\r\n\r\n")

    def test_http_022_espacio_antes_de_la_extension_se_admite(self):
        crudo = (b"POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                 b"3 ;a=b\r\nabc\r\n0\r\n\r\n")
        self.assertEqual(leer(crudo).cuerpo, b"abc")

    def test_el_limite_del_cuerpo_se_configura(self):
        with self.assertRaises(Rechazo) as fallo:
            leer(b"POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 11\r\n\r\n01234567890", 10)
        self.assertEqual(fallo.exception.estado, 413)
        with self.assertRaises(Rechazo) as fallo:
            leer(b"POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                 b"6\r\n012345\r\n6\r\n012345\r\n0\r\n\r\n", 10)
        self.assertEqual(fallo.exception.estado, 413)


class Respuestas(unittest.TestCase):

    def test_el_servidor_es_dueno_del_encuadre(self):
        respuesta = Respuesta(200, [("Content-Length", "99"), ("transfer-encoding", "chunked"),
                                    ("Connection", "keep-alive"), ("X-A", "1")], b"ok")
        cabeza = serializar(respuesta, conexion="close").decode("latin-1").lower()
        self.assertEqual(cabeza.count("content-length"), 1)
        self.assertIn("content-length: 2\r\n", cabeza)
        self.assertNotIn("transfer-encoding", cabeza)
        self.assertEqual(cabeza.count("connection"), 1)
        self.assertIn("connection: close\r\n", cabeza)
        self.assertIn("x-a: 1\r\n", cabeza)

    def test_python_anterior_a_3_12_se_rechaza_con_un_mensaje_claro(self):
        with self.assertRaises(RuntimeError) as fallo:
            cero.comprobar_version((3, 11, 9))
        self.assertIn("3.12", str(fallo.exception))
        cero.comprobar_version((3, 12, 0))


class Plazos(unittest.TestCase):

    def arrancar(self, **ajustes):
        servidor, hilo = arrancar(**ajustes)
        self.addCleanup(hilo.join, 5)
        self.addCleanup(servidor.cerrar)
        return servidor

    def test_slowloris_se_corta_en_el_plazo_de_la_cabecera(self):
        servidor = self.arrancar(espera=0.5, plazo_cabecera=1.0)
        tiempo, respuesta = gotear(servidor.puerto, b"GET / HTTP/1.1\r\nHost: x\r\nX-A: ", 0.3, 4)
        self.assertIsNotNone(respuesta)
        self.assertLess(tiempo, 2.0)
        self.assertTrue(respuesta == b"" or respuesta.startswith(b"HTTP/1.1 408 "), respuesta)

    def test_un_cuerpo_que_gotea_se_corta_en_su_plazo(self):
        servidor = self.arrancar(espera=0.5, plazo_cuerpo=1.0)
        cabeza = b"POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\n"
        tiempo, respuesta = gotear(servidor.puerto, cabeza, 0.3, 4)
        self.assertIsNotNone(respuesta)
        self.assertLess(tiempo, 2.0)

    def test_http_1_0_keep_alive_lo_dice_y_lo_cumple(self):
        servidor = self.arrancar()
        with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=5) as conexion:
            conexion.sendall(b"GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n" * 2)
            with conexion.makefile("rb") as archivo:
                for _ in range(2):
                    estado, cabeceras, largo = leer_respuesta(archivo)
                    self.assertEqual((estado, cabeceras.get("connection")), (200, "keep-alive"))
                    self.assertEqual(archivo.read(largo), b"ok")


class Agotamiento(unittest.TestCase):

    def test_sin_descriptores_el_servidor_sigue_escuchando(self):
        puerto = puerto_libre()
        duro = resource.getrlimit(resource.RLIMIT_NOFILE)[1]
        proceso = subprocess.Popen(
            [sys.executable, "-m", "cero.conforme", str(puerto)], cwd=PYTHON,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            preexec_fn=lambda: resource.setrlimit(resource.RLIMIT_NOFILE, (40, duro)))
        self.addCleanup(proceso.wait, 5)
        self.addCleanup(proceso.kill)
        limite = time.monotonic() + 10
        while True:
            try:
                pedir(puerto, GET)
                break
            except OSError:
                self.assertLess(time.monotonic(), limite)
                time.sleep(0.05)
        abiertas = []
        for _ in range(60):
            try:
                abiertas.append(socket.create_connection(("127.0.0.1", puerto), timeout=2))
            except OSError:
                pass
        time.sleep(0.5)
        for conexion in abiertas:
            conexion.close()
        limite = time.monotonic() + 10
        while True:
            try:
                self.assertEqual(pedir(puerto, GET)[0], 200)
                return
            except OSError:
                self.assertLess(time.monotonic(), limite, "el servidor dejó de escuchar")
                time.sleep(0.1)

    def test_un_hilo_que_no_arranca_no_tumba_ni_gasta_el_cupo(self):
        servidor, hilo = arrancar(maximo_conexiones=1)
        self.addCleanup(hilo.join, 5)
        self.addCleanup(servidor.cerrar)
        with mock.patch.object(threading.Thread, "start", side_effect=RuntimeError("sin hilos")):
            with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=3) as conexion:
                conexion.sendall(GET)
                try:
                    self.assertEqual(conexion.recv(100), b"")
                except ConnectionResetError:
                    pass
        self.assertTrue(hilo.is_alive())
        self.assertEqual(pedir(servidor.puerto, GET)[0], 200)

    def test_el_503_llega_entero_aunque_quede_peticion_sin_leer(self):
        servidor, hilo = arrancar(maximo_conexiones=1)
        self.addCleanup(hilo.join, 5)
        self.addCleanup(servidor.cerrar)
        with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=5):
            time.sleep(0.3)
            cuerpo = b"x" * 200000
            with socket.create_connection(("127.0.0.1", servidor.puerto), timeout=5) as segunda:
                segunda.sendall(b"POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: %d\r\n\r\n"
                                % len(cuerpo) + cuerpo)
                time.sleep(0.3)
                self.assertTrue(segunda.recv(100).startswith(b"HTTP/1.1 503 "))


class Empaquetado(unittest.TestCase):

    def test_ni_enlaces_ni_pyz_ni_herramientas_viajan_dentro(self):
        import tempfile
        import zipfile
        from cero.empaquetar import empaquetar
        with tempfile.TemporaryDirectory() as tmp:
            carpeta = pathlib.Path(tmp, "miapp")
            carpeta.mkdir()
            carpeta.joinpath("app.py").write_text("def main():\n    pass\n")
            carpeta.joinpath("viejo.pyz").write_bytes(b"PK")
            salida = pathlib.Path(tmp, "app.pyz")
            empaquetar(carpeta, salida, "app:main")
            nombres = zipfile.ZipFile(salida).namelist()
            self.assertIn("app.py", nombres)
            self.assertNotIn("viejo.pyz", nombres)
            self.assertNotIn("cero/empaquetar.py", nombres)
            self.assertNotIn("cero/conforme.py", nombres)
            self.assertIn("cero/servidor.py", nombres)

            with self.assertRaises(ValueError):
                empaquetar(carpeta, carpeta / "dentro.pyz", "app:main")

            secreto = pathlib.Path(tmp, "secreto")
            secreto.mkdir()
            carpeta.joinpath("enlace").symlink_to(secreto)
            with self.assertRaises(ValueError) as fallo:
                empaquetar(carpeta, pathlib.Path(tmp, "otra.pyz"), "app:main")
            self.assertIn("enlace", str(fallo.exception))


if __name__ == "__main__":
    unittest.main()
