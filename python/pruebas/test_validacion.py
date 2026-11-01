import json
import unittest

from cero.peticion import Peticion
from cero.ruta import Router
from cero.servidor import Servidor
from cero.validacion import Campo, validado

ESQUEMA = {
    "nombre": Campo(str, maximo=20),
    "edad": Campo(int, requerido=False, minimo=0, maximo=150),
    "correo": Campo(str, requerido=False, patron=r"[^@\s]+@[^@\s]+"),
    "activo": Campo(bool, requerido=False),
}


class Validacion(unittest.TestCase):

    def setUp(self):
        self.recibidas = []

        def registrar(p):
            self.recibidas.append((bytes(p.cuerpo), p.datos))
            return p.datos["nombre"]
        router = Router()
        router.agregar("POST", "/registro", validado(ESQUEMA, registrar))
        self.servidor = Servidor(router)

    def enviar(self, cuerpo, tipo="application/json"):
        if not isinstance(cuerpo, bytes):
            cuerpo = json.dumps(cuerpo).encode()
        cabeceras = [("host", "x")] + ([("content-type", tipo)] if tipo else [])
        return self.servidor.responder(Peticion("POST", "/registro", "/registro", "",
                                                "HTTP/1.1", cabeceras, bytearray(cuerpo)))

    def errores(self, respuesta):
        self.assertEqual(respuesta.estado, 422)
        self.assertIn(("Content-Type", "application/json"), respuesta.cabeceras)
        return json.loads(respuesta.cuerpo)["campos"]

    def test_seg_027_un_cuerpo_valido_llega_sin_alterar(self):
        crudo = b'{"nombre": "Ana",  "edad": 30, "correo": "ana@x.pe", "activo": true}'
        r = self.enviar(crudo, "application/json; charset=utf-8")
        self.assertEqual((r.estado, r.cuerpo), (200, b"Ana"))
        self.assertEqual(self.recibidas, [(crudo, {"nombre": "Ana", "edad": 30,
                                                   "correo": "ana@x.pe", "activo": True})])

    def test_seg_027_los_opcionales_pueden_faltar(self):
        self.assertEqual(self.enviar({"nombre": "Ana"}).estado, 200)
        self.assertEqual(self.recibidas[0][1], {"nombre": "Ana"})

    def test_seg_028_invalido_es_422_y_dice_que_campo_y_por_que(self):
        self.assertEqual(self.errores(self.enviar({"nombre": ""})),
                         {"nombre": "es obligatorio"})
        self.assertEqual(self.recibidas, [])

    def test_seg_028_detalla_todos_los_campos_que_fallan(self):
        errores = self.errores(self.enviar({"edad": -1, "correo": "sin-arroba",
                                            "activo": "si", "rol": "admin"}))
        self.assertEqual(errores, {"nombre": "es obligatorio",
                                   "edad": "debe ser al menos 0",
                                   "correo": "no tiene el formato esperado",
                                   "activo": "debe ser verdadero o falso",
                                   "rol": "no se admite"})

    def test_seg_028_los_tipos_no_se_confunden(self):
        self.assertEqual(self.errores(self.enviar({"nombre": "Ana", "edad": True})),
                         {"edad": "debe ser un entero"})
        self.assertEqual(self.errores(self.enviar({"nombre": "Ana", "edad": 1.5})),
                         {"edad": "debe ser un entero"})
        self.assertEqual(self.errores(self.enviar({"nombre": 7})), {"nombre": "debe ser texto"})
        self.assertEqual(self.errores(self.enviar({"nombre": "a" * 21})),
                         {"nombre": "debe tener como mucho 20 caracteres"})

    def test_seg_028_un_json_que_no_es_objeto_es_422(self):
        r = self.enviar([1, 2])
        self.assertEqual(r.estado, 422)
        self.assertIn("objeto", json.loads(r.cuerpo)["error"])

    def test_lo_que_no_se_entiende_es_400_y_no_422(self):
        for crudo in (b"{", b"\xff\xfe", b'{"nombre": NaN}', b'{"nombre": "a", "nombre": "b"}',
                      b"[" * 100000, b'{"edad": 1' + b"0" * 5000 + b"}"):
            with self.subTest(crudo=crudo[:20]):
                self.assertEqual(self.enviar(crudo).estado, 400)
        self.assertEqual(self.recibidas, [])

    def test_sin_tipo_json_es_415(self):
        self.assertEqual(self.enviar({"nombre": "Ana"}, "text/plain").estado, 415)
        self.assertEqual(self.enviar({"nombre": "Ana"}, None).estado, 415)


if __name__ == "__main__":
    unittest.main()
