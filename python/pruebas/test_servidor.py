import contextlib
import io
import socket
import struct
import threading
import unittest

from cero.peticion import Peticion
from cero.respuesta import Respuesta
from cero.ruta import Router
from cero.servidor import Servidor

GRANDE = b"x" * (16 * 1024 * 1024)


def peticion(verbo, camino):
    return Peticion(verbo, camino, camino, "", "HTTP/1.1", [("host", "x")])


def router_de_prueba():
    router = Router()
    router.agregar("GET", "/", lambda p: "ok")
    router.agregar("GET", "/usuarios/{id}", lambda p: "usuario " + p.variables["id"])
    router.agregar("DELETE", "/usuarios/{id}", lambda p: "borrado")
    router.agregar("GET", "/grande", lambda p: Respuesta(200, [], GRANDE))
    router.agregar("POST", "/eco", lambda p: p.cuerpo)
    router.agregar("GET", "/falla", lambda p: 1 / 0)
    return router


def arrancar():
    servidor = Servidor(router_de_prueba())
    hilo = threading.Thread(target=servidor.escuchar, args=(0,), daemon=True)
    hilo.start()
    servidor.listo.wait(5)
    return servidor, hilo


def leer_respuesta(archivo):
    linea = archivo.readline()
    cabeceras = {}
    while (renglon := archivo.readline()) not in (b"\r\n", b""):
        nombre, valor = renglon.decode("latin-1").split(":", 1)
        cabeceras[nombre.lower()] = valor.strip()
    largo = int(cabeceras.get("content-length", "0"))
    return int(linea.split()[1]), cabeceras, largo


def pedir(puerto, crudo):
    with socket.create_connection(("127.0.0.1", puerto), timeout=5) as conexion:
        conexion.sendall(crudo)
        with conexion.makefile("rb") as archivo:
            estado, cabeceras, largo = leer_respuesta(archivo)
            return estado, cabeceras, archivo.read(largo)


class Pipeline(unittest.TestCase):

    def test_rut_012_ruta_inexistente_404(self):
        respuesta = Servidor(router_de_prueba()).responder(peticion("GET", "/nada"))
        self.assertEqual(respuesta.estado, 404)

    def test_rut_013_verbo_no_admitido_405_con_allow(self):
        respuesta = Servidor(router_de_prueba()).responder(peticion("PUT", "/usuarios/1"))
        self.assertEqual(respuesta.estado, 405)
        self.assertIn(("Allow", "DELETE, GET, HEAD"), respuesta.cabeceras)

    def test_rut_002_la_accion_recibe_las_variables(self):
        respuesta = Servidor(router_de_prueba()).responder(peticion("GET", "/usuarios/7"))
        self.assertEqual((respuesta.estado, respuesta.cuerpo), (200, b"usuario 7"))

    def test_un_fallo_no_filtra_el_mensaje(self):
        with contextlib.redirect_stderr(io.StringIO()) as registro:
            respuesta = Servidor(router_de_prueba()).responder(peticion("GET", "/falla"))
        self.assertIn("ZeroDivisionError", registro.getvalue())
        self.assertEqual(respuesta.estado, 500)
        self.assertNotIn(b"division", respuesta.cuerpo)


class Socket(unittest.TestCase):

    def setUp(self):
        self.servidor, self.hilo = arrancar()
        self.puerto = self.servidor.puerto

    def tearDown(self):
        self.servidor.cerrar()
        self.hilo.join(5)

    def test_rut_011_head_responde_sin_cuerpo(self):
        with socket.create_connection(("127.0.0.1", self.puerto), timeout=5) as conexion:
            conexion.sendall(b"HEAD / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
            with conexion.makefile("rb") as archivo:
                estado, cabeceras, _ = leer_respuesta(archivo)
                self.assertEqual(estado, 200)
                self.assertEqual(cabeceras["content-length"], "2")
                self.assertEqual(archivo.read(), b"")

    def test_rut_013_405_por_el_socket_lleva_allow(self):
        estado, cabeceras, _ = pedir(self.puerto, b"PUT /usuarios/1 HTTP/1.1\r\nHost: x\r\n\r\n")
        self.assertEqual((estado, cabeceras["allow"]), (405, "DELETE, GET, HEAD"))

    def test_keep_alive_atiende_dos_peticiones_en_la_misma_conexion(self):
        with socket.create_connection(("127.0.0.1", self.puerto), timeout=5) as conexion:
            conexion.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\nGET /usuarios/3 HTTP/1.1\r\nHost: x\r\n\r\n")
            with conexion.makefile("rb") as archivo:
                _, _, largo = leer_respuesta(archivo)
                self.assertEqual(archivo.read(largo), b"ok")
                _, _, largo = leer_respuesta(archivo)
                self.assertEqual(archivo.read(largo), b"usuario 3")

    def test_http_022_chunked_llega_entero(self):
        crudo = (b"POST /eco HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                 b"5\r\nhola \r\n4;ext=1\r\nmund\r\n1\r\no\r\n0\r\nX-Fin: si\r\n\r\n")
        self.assertEqual(pedir(self.puerto, crudo)[2], b"hola mundo")

    def test_expect_100_continue_pide_el_cuerpo(self):
        with socket.create_connection(("127.0.0.1", self.puerto), timeout=5) as conexion:
            conexion.sendall(b"POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n"
                             b"Expect: 100-continue\r\n\r\n")
            with conexion.makefile("rb") as archivo:
                self.assertEqual(archivo.readline(), b"HTTP/1.1 100 Continue\r\n")
                self.assertEqual(archivo.readline(), b"\r\n")
                conexion.sendall(b"cuat")
                _, _, largo = leer_respuesta(archivo)
                self.assertEqual(archivo.read(largo), b"cuat")

    def test_cuerpo_que_miente_no_cuelga_al_servidor(self):
        with socket.create_connection(("127.0.0.1", self.puerto), timeout=5) as conexion:
            conexion.sendall(b"POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\ncorto")
            conexion.shutdown(socket.SHUT_WR)
            self.assertTrue(conexion.recv(100).startswith(b"HTTP/1.1 400 "))
        self.assertEqual(pedir(self.puerto, b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")[0], 200)

    def test_cliente_que_corta_a_mitad_no_tumba_el_servidor(self):
        for _ in range(3):
            conexion = socket.create_connection(("127.0.0.1", self.puerto), timeout=5)
            conexion.sendall(b"GET /grande HTTP/1.1\r\nHost: x\r\n\r\n")
            conexion.recv(1024)
            conexion.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            conexion.close()
        estado, _, cuerpo = pedir(self.puerto, b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        self.assertEqual((estado, cuerpo), (200, b"ok"))
        self.assertTrue(self.hilo.is_alive())


class Arranque(unittest.TestCase):

    def test_puerto_ocupado_da_un_error_claro(self):
        with socket.create_server(("127.0.0.1", 0)) as ocupante:
            puerto = ocupante.getsockname()[1]
            antes = threading.active_count()
            with self.assertRaises(OSError) as fallo:
                Servidor(Router()).escuchar(puerto)
            self.assertIn("ocupado", str(fallo.exception))
            self.assertIn(str(puerto), str(fallo.exception))
            self.assertEqual(threading.active_count(), antes)

    def test_cerrar_termina_escuchar(self):
        servidor, hilo = arrancar()
        servidor.cerrar()
        hilo.join(5)
        self.assertFalse(hilo.is_alive())
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", servidor.puerto), timeout=1).close()

    def test_cerrar_antes_de_arrancar_no_deja_hilos(self):
        servidor = Servidor(Router())
        servidor.cerrar()
        hilo = threading.Thread(target=servidor.escuchar, args=(0,), daemon=True)
        hilo.start()
        hilo.join(5)
        self.assertFalse(hilo.is_alive())


if __name__ == "__main__":
    unittest.main()
