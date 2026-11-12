import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request

AQUI = pathlib.Path(__file__).resolve().parent
CERO = AQUI.parent.parent / "python"


def puerto_libre():
    with socket.create_server(("127.0.0.1", 0)) as oyente:
        return oyente.getsockname()[1]


def pedir(puerto, camino, cuerpo=None):
    peticion = urllib.request.Request(f"http://127.0.0.1:{puerto}{camino}", data=cuerpo)
    with urllib.request.urlopen(peticion, timeout=5) as respuesta:
        return respuesta.status, respuesta.read()


class PuntaAPunta(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.puerto = puerto_libre()
        entorno = {**os.environ, "PUERTO": str(cls.puerto), "PYTHONPATH": str(CERO)}
        cls.proceso = subprocess.Popen([sys.executable, "-B", str(AQUI / "app.py")], env=entorno)
        limite = time.monotonic() + 10
        while True:
            try:
                socket.create_connection(("127.0.0.1", cls.puerto), timeout=1).close()
                return
            except OSError:
                if time.monotonic() > limite:
                    cls.proceso.kill()
                    raise
                time.sleep(0.05)

    @classmethod
    def tearDownClass(cls):
        cls.proceso.terminate()
        cls.proceso.wait(5)

    def test_raiz(self):
        estado, cuerpo = pedir(self.puerto, "/")
        self.assertEqual(estado, 200)
        self.assertEqual(json.loads(cuerpo), {"hola": "mundo"})

    def test_saludo(self):
        _, cuerpo = pedir(self.puerto, "/saludo/ana")
        self.assertEqual(json.loads(cuerpo), {"saludo": "hola, ana"})

    def test_eco(self):
        enviado = {"a": [1, 2], "b": {"c": "ñ"}}
        estado, cuerpo = pedir(self.puerto, "/eco", json.dumps(enviado).encode())
        self.assertEqual(estado, 200)
        self.assertEqual(json.loads(cuerpo), enviado)


class EnUnPyz(unittest.TestCase):

    def test_un_solo_archivo_responde_con_python_aislado(self):
        with tempfile.TemporaryDirectory() as tmp:
            carpeta = pathlib.Path(tmp, "app")
            carpeta.mkdir()
            shutil.copy(AQUI / "app.py", carpeta)
            pyz = pathlib.Path(tmp, "app.pyz")
            subprocess.run([sys.executable, "-B", "-m", "cero.empaquetar", str(carpeta), str(pyz),
                            "app:main"], cwd=CERO, check=True, capture_output=True, timeout=30)
            puerto = puerto_libre()
            proceso = subprocess.Popen([sys.executable, "-I", str(pyz)],
                                       env={"PUERTO": str(puerto)})
            try:
                limite = time.monotonic() + 10
                while True:
                    try:
                        _, cuerpo = pedir(puerto, "/saludo/pyz")
                        break
                    except OSError:
                        if time.monotonic() > limite:
                            raise
                        time.sleep(0.05)
                self.assertEqual(json.loads(cuerpo), {"saludo": "hola, pyz"})
            finally:
                proceso.terminate()
                proceso.wait(5)


if __name__ == "__main__":
    unittest.main()
