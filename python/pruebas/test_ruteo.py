import unittest

from cero.ruta import NoEncontrado, NoPermitido, Patron, Resuelto, Router


def accion(nombre):
    return lambda peticion: nombre


def router_de_usuarios():
    router = Router()
    router.agregar("GET", "/usuarios", accion("lista"))
    router.agregar("GET", "/usuarios/nuevo", accion("form"))
    router.agregar("GET", "/usuarios/{id}", accion("uno"))
    router.agregar("GET", "/usuarios/{id}/posts/{slug}", accion("post"))
    router.agregar("POST", "/usuarios", accion("creado"))
    router.agregar("DELETE", "/usuarios/{id}", accion("borrado"))
    return router


class Patrones(unittest.TestCase):

    def test_rut_001_mismo_numero_de_segmentos(self):
        self.assertEqual(Patron("/").casar("/"), {})
        self.assertIsNone(Patron("/").casar("/a"))
        patron = Patron("/usuarios/{id}")
        self.assertIsNotNone(patron.casar("/usuarios/7"))
        self.assertIsNone(patron.casar("/usuarios/7/x"))
        self.assertIsNone(patron.casar("/usuarios"))

    def test_rut_001_literal_distinto_no_casa(self):
        self.assertIsNone(Patron("/usuarios/{id}").casar("/clientes/7"))

    def test_rut_002_variable_por_su_nombre(self):
        self.assertEqual(Patron("/usuarios/{id}").casar("/usuarios/7"), {"id": "7"})

    def test_rut_003_varias_variables(self):
        variables = Patron("/a/{x}/b/{y}").casar("/a/1/b/2")
        self.assertEqual(variables["x"], "1")
        self.assertEqual(variables["y"], "2")

    def test_rut_004_barra_final(self):
        patron = Patron("/a/{n}")
        self.assertEqual(patron.casar("/a/7"), patron.casar("/a/7/"))
        self.assertEqual(patron.casar("/a/7/"), {"n": "7"})
        self.assertEqual(Patron("/a/{n}/").casar("/a/7"), {"n": "7"})

    def test_rut_005_comodin_final(self):
        patron = Patron("/estaticos/*")
        self.assertEqual(patron.casar("/estaticos/css/a.css")["*"], "css/a.css")
        self.assertEqual(patron.casar("/estaticos/a.css")["*"], "a.css")

    def test_rut_006_comodin_en_medio_falla_al_construir(self):
        with self.assertRaises(ValueError):
            Patron("/a/*/b")

    def test_rut_007_variable_sin_cerrar_falla_al_construir(self):
        for malo in ("/a/{id", "/a/id}", "/a/{}", "/a/{i{d}", "sin-barra"):
            with self.subTest(malo=malo), self.assertRaises(ValueError):
                Patron(malo)


class Resolucion(unittest.TestCase):

    def test_rut_008_literal_gana_a_variable(self):
        router = router_de_usuarios()
        self.assertEqual(router.resolver("GET", "/usuarios/nuevo").accion(None), "form")
        self.assertEqual(router.resolver("GET", "/usuarios/otro").accion(None), "uno")

    def test_rut_008_el_orden_de_registro_no_importa(self):
        router = Router()
        router.agregar("GET", "/usuarios/{id}", accion("uno"))
        router.agregar("GET", "/usuarios/nuevo", accion("form"))
        self.assertEqual(router.resolver("GET", "/usuarios/nuevo").accion(None), "form")

    def test_rut_008_el_literal_de_otro_verbo_no_tapa_la_variable(self):
        router = Router()
        router.agregar("GET", "/usuarios/{id}", accion("uno"))
        router.agregar("DELETE", "/usuarios/nuevo", accion("borrar"))
        self.assertEqual(router.resolver("GET", "/usuarios/nuevo").accion(None), "uno")

    def test_rut_002_resolver_entrega_las_variables(self):
        resuelto = router_de_usuarios().resolver("GET", "/usuarios/42/posts/hola")
        self.assertIsInstance(resuelto, Resuelto)
        self.assertEqual(resuelto.variables, {"id": "42", "slug": "hola"})

    def test_rut_009_404_distinto_de_405(self):
        router = router_de_usuarios()
        self.assertIsInstance(router.resolver("GET", "/nada"), NoEncontrado)
        self.assertIsInstance(router.resolver("PUT", "/usuarios/1"), NoPermitido)

    def test_rut_010_enumera_los_verbos(self):
        resultado = router_de_usuarios().resolver("PUT", "/usuarios/1")
        self.assertEqual(resultado.verbos, ["DELETE", "GET", "HEAD"])

    def test_rut_011_head_contra_get(self):
        resuelto = router_de_usuarios().resolver("HEAD", "/usuarios")
        self.assertIsInstance(resuelto, Resuelto)
        self.assertEqual(resuelto.accion(None), "lista")

    def test_rut_011_head_explicito_gana_a_get(self):
        router = Router()
        router.agregar("GET", "/a", accion("get"))
        router.agregar("HEAD", "/a", accion("head"))
        self.assertEqual(router.resolver("HEAD", "/a").accion(None), "head")

    def test_ruta_duplicada_se_rechaza(self):
        router = Router()
        router.agregar("GET", "/a", accion("1"))
        with self.assertRaises(ValueError):
            router.agregar("GET", "/a/", accion("2"))


if __name__ == "__main__":
    unittest.main()
