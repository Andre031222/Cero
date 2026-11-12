import json
import unittest

from cero.peticion import Peticion
from cero.respuesta import como_respuesta, en_json
from cero.ruta import Resuelto, Router
from cero.servidor import Servidor


def peticion(verbo, camino, cuerpo=b""):
    return Peticion(verbo, camino, camino, "", "HTTP/1.1", [("host", "x")], bytearray(cuerpo))


class FormaCorta(unittest.TestCase):

    def test_get_registra_en_una_linea(self):
        router = Router()
        router.get("/", lambda p: "raíz")
        self.assertIsInstance(router.resolver("GET", "/"), Resuelto)

    def test_cada_verbo_registra_el_suyo(self):
        router = Router()
        for verbo in ("get", "post", "put", "delete", "patch"):
            getattr(router, verbo)("/x", lambda p, v=verbo: v)
        for verbo in ("GET", "POST", "PUT", "DELETE", "PATCH"):
            self.assertEqual(router.resolver(verbo, "/x").accion(None), verbo.lower())

    def test_como_decorador(self):
        router = Router()

        @router.get("/d")
        def accion(p):
            return "decorada"

        self.assertEqual(accion(None), "decorada")
        self.assertEqual(router.resolver("GET", "/d").accion(None), "decorada")

    def test_convive_con_agregar(self):
        router = Router()
        router.agregar("GET", "/a", lambda p: "a")
        router.get("/b", lambda p: "b")
        with self.assertRaises(ValueError):
            router.get("/a", lambda p: "otra")

    def test_patron_invalido_falla_al_registrar(self):
        with self.assertRaises(ValueError):
            Router().get("sin-barra", lambda p: "x")


class Json(unittest.TestCase):

    def test_diccionario_se_vuelve_json(self):
        respuesta = como_respuesta({"hola": "mundo"})
        self.assertEqual(respuesta.estado, 200)
        self.assertEqual(respuesta.cuerpo, b'{"hola":"mundo"}')
        self.assertIn(("Content-Type", "application/json"), respuesta.cabeceras)

    def test_lista_se_vuelve_json(self):
        self.assertEqual(como_respuesta([1, 2]).cuerpo, b"[1,2]")

    def test_en_json_con_estado(self):
        respuesta = en_json({"a": 1}, 201)
        self.assertEqual(respuesta.estado, 201)
        self.assertEqual(json.loads(respuesta.cuerpo), {"a": 1})

    def test_tildes_viajan_en_utf8(self):
        self.assertEqual(en_json({"s": "añá"}).cuerpo.decode(), '{"s":"añá"}')

    def test_otros_tipos_siguen_sin_valer(self):
        with self.assertRaises(TypeError):
            como_respuesta(3)

    def test_peticion_json_lee_el_cuerpo(self):
        self.assertEqual(peticion("POST", "/", b'{"a": [1]}').json(), {"a": [1]})

    def test_servidor_responde_json_sin_socket(self):
        router = Router()
        router.get("/saludo/{nombre}", lambda p: {"saludo": "hola, " + p.variables["nombre"]})
        router.post("/eco", lambda p: en_json(p.json()))
        servidor = Servidor(router)
        self.assertEqual(servidor.responder(peticion("GET", "/saludo/ana")).cuerpo,
                         b'{"saludo":"hola, ana"}')
        self.assertEqual(servidor.responder(peticion("POST", "/eco", b'{"x": 1}')).cuerpo,
                         b'{"x":1}')

    def test_json_malo_en_el_cuerpo_es_400(self):
        router = Router()
        router.post("/eco", lambda p: en_json(p.json()))
        respuesta = Servidor(router).responder(peticion("POST", "/eco", b"{no"))
        self.assertEqual(respuesta.estado, 400)
