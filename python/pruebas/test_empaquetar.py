import pathlib
import socket
import subprocess
import sys
import tempfile
import time
import unittest

PYTHON = pathlib.Path(__file__).resolve().parent.parent

APLICACION = """import sys

from cero.ruta import Router
from cero.servidor import Servidor


def main():
    router = Router()
    router.agregar("GET", "/hola", lambda peticion: "hola desde el pyz")
    Servidor(router).escuchar(int(sys.argv[1]))
"""


def puerto_libre():
    with socket.create_server(("127.0.0.1", 0)) as oyente:
        return oyente.getsockname()[1]


def empaquetar(*argumentos):
    return subprocess.run([sys.executable, "-m", "cero.empaquetar", *argumentos],
                          cwd=PYTHON, capture_output=True, text=True, timeout=30)


def pedir(puerto):
    limite = time.monotonic() + 10
    while True:
        try:
            with socket.create_connection(("127.0.0.1", puerto), timeout=5) as conexion:
                conexion.sendall(b"GET /hola HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                datos = b""
                while trozo := conexion.recv(4096):
                    datos += trozo
                return datos
        except ConnectionRefusedError:
            if time.monotonic() > limite:
                raise
            time.sleep(0.05)


class Empaquetar(unittest.TestCase):

    def test_un_solo_archivo_corre_sin_nada_instalado(self):
        with tempfile.TemporaryDirectory() as tmp:
            carpeta = pathlib.Path(tmp, "miapp")
            carpeta.mkdir()
            carpeta.joinpath("app.py").write_text(APLICACION)
            salida = pathlib.Path(tmp, "app.pyz")
            hecho = empaquetar(str(carpeta), str(salida), "app:main")
            self.assertEqual(hecho.returncode, 0, hecho.stderr)
            self.assertTrue(salida.is_file())

            puerto = puerto_libre()
            proceso = subprocess.Popen([sys.executable, "-I", str(salida), str(puerto)], cwd=tmp,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                respuesta = pedir(puerto)
            finally:
                proceso.terminate()
                proceso.wait(5)
                proceso.stderr.close()
            self.assertTrue(respuesta.startswith(b"HTTP/1.1 200 "), respuesta)
            self.assertTrue(respuesta.endswith(b"hola desde el pyz"))

    def test_sin_punto_de_entrada_falla_con_un_mensaje_claro(self):
        with tempfile.TemporaryDirectory() as tmp:
            carpeta = pathlib.Path(tmp, "miapp")
            carpeta.mkdir()
            carpeta.joinpath("app.py").write_text(APLICACION)
            hecho = empaquetar(str(carpeta), str(pathlib.Path(tmp, "app.pyz")))
            self.assertNotEqual(hecho.returncode, 0)
            self.assertIn("__main__.py", hecho.stderr)
            self.assertFalse(pathlib.Path(tmp, "app.pyz").exists())


if __name__ == "__main__":
    unittest.main()
