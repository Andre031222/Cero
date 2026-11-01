import unittest
from unittest import mock

from cero.peticion import Peticion
from cero.respuesta import Respuesta
from cero.ruta import Router
from cero import seguridad
from cero.seguridad import Cabeceras, Cors, Csrf
from cero.servidor import Servidor
from pruebas.test_servidor import arrancar, pedir


def peticion(verbo, camino, *cabeceras, cuerpo=b"", cliente="10.0.0.1", segura=False):
    lista = [("host", "x"), *((n.lower(), v) for n, v in cabeceras)]
    return Peticion(verbo, camino, camino, "", "HTTP/1.1", lista, bytearray(cuerpo),
                    segura=segura, cliente=cliente)


def router():
    r = Router()
    r.agregar("GET", "/", lambda p: "ok")
    r.agregar("GET", "/falla", lambda p: 1 / 0)
    r.agregar("GET", "/propia", lambda p: Respuesta(200, [("X-Frame-Options", "SAMEORIGIN")]))
    r.agregar("GET", "/variada", lambda p: Respuesta(200, [("Vary", "Accept-Encoding")]))
    r.agregar("POST", "/guardar", lambda p: "guardado")
    return r


def cabeceras(respuesta):
    return {n.lower(): v for n, v in respuesta.cabeceras}


def nombres(respuesta):
    return [n.lower() for n, _ in respuesta.cabeceras]


class CabecerasDeSeguridad(unittest.TestCase):

    def responder(self, *args, servidor=None, **kwargs):
        return (servidor or Servidor(router())).responder(peticion(*args, **kwargs))

    def test_seg_001_nosniff_en_toda_respuesta_del_pipeline(self):
        for camino, estado in (("/", 200), ("/nada", 404), ("/falla", 500)):
            with self.subTest(camino=camino):
                with mock.patch("sys.stderr"):
                    r = self.responder("GET", camino)
                self.assertEqual(r.estado, estado)
                self.assertEqual(cabeceras(r)["x-content-type-options"], "nosniff")
        r = self.responder("PUT", "/")
        self.assertEqual((r.estado, cabeceras(r)["x-content-type-options"]), (405, "nosniff"))

    def test_seg_001_nosniff_tambien_en_el_rechazo_del_parser(self):
        servidor, _ = arrancar()
        try:
            estado, cab, _ = pedir(servidor.puerto, b"GET / HTTP/1.1\r\n\r\n")
        finally:
            servidor.cerrar()
        self.assertEqual(estado, 400)
        self.assertEqual(cab.get("x-content-type-options"), "nosniff")
        self.assertEqual(cab.get("x-frame-options"), "DENY")

    def test_seg_002_impide_el_enmarcado(self):
        self.assertEqual(cabeceras(self.responder("GET", "/"))["x-frame-options"], "DENY")

    def test_seg_003_declara_politica_de_referente(self):
        self.assertEqual(cabeceras(self.responder("GET", "/"))["referrer-policy"],
                         "strict-origin-when-cross-origin")

    def test_seg_004_deniega_camara_microfono_y_ubicacion(self):
        politica = cabeceras(self.responder("GET", "/"))["permissions-policy"]
        for permiso in ("camera=()", "microphone=()", "geolocation=()"):
            self.assertIn(permiso, politica)

    def test_seg_005_sin_tls_no_hay_hsts(self):
        self.assertNotIn("strict-transport-security", cabeceras(self.responder("GET", "/")))
        segura = cabeceras(self.responder("GET", "/", segura=True))
        self.assertIn("max-age=", segura["strict-transport-security"])

    def test_seg_005_por_el_socket_no_hay_hsts(self):
        servidor, _ = arrancar()
        try:
            estado, cab, _ = pedir(servidor.puerto, b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        finally:
            servidor.cerrar()
        self.assertEqual(estado, 200)
        self.assertNotIn("strict-transport-security", cab)

    def test_seg_006_sin_csp_declarada_no_se_inventa(self):
        for segura in (False, True):
            r = self.responder("GET", "/", segura=segura)
            self.assertNotIn("content-security-policy", cabeceras(r))

    def test_seg_007_aflojar_el_enmarcado_y_declarar_csp_sin_tocar_el_resto(self):
        base = cabeceras(self.responder("GET", "/"))
        medida = Servidor(router(), cabeceras=Cabeceras(enmarcado="SAMEORIGIN",
                                                        csp="default-src 'self'"))
        r = cabeceras(self.responder("GET", "/", servidor=medida))
        self.assertEqual(r["x-frame-options"], "SAMEORIGIN")
        self.assertEqual(r["content-security-policy"], "default-src 'self'")
        for nombre in ("x-content-type-options", "referrer-policy", "permissions-policy"):
            self.assertEqual(r[nombre], base[nombre])

    def test_la_cabecera_que_pone_la_accion_gana_y_no_sale_dos_veces(self):
        r = self.responder("GET", "/propia")
        self.assertEqual(nombres(r).count("x-frame-options"), 1)
        self.assertEqual(cabeceras(r)["x-frame-options"], "SAMEORIGIN")

    def test_se_pueden_apagar(self):
        r = self.responder("GET", "/", servidor=Servidor(router(), cabeceras=None))
        self.assertNotIn("x-content-type-options", cabeceras(r))


def de_cors(respuesta):
    return {n: v for n, v in cabeceras(respuesta).items() if n.startswith("access-control-")}


class CorsTests(unittest.TestCase):

    def setUp(self):
        self.llamadas = 0

        def contar(p):
            self.llamadas += 1
            return "ok"
        r = router()
        r.agregar("GET", "/api", contar)
        self.router = r

    def responder(self, cors, *args, **kwargs):
        return Servidor(self.router, cors=cors).responder(peticion(*args, **kwargs))

    def lista(self, **ajustes):
        return Cors(["https://app.pe"], **ajustes)

    def preflight(self, cors, origen):
        return self.responder(cors, "OPTIONS", "/api", ("Origin", origen),
                              ("Access-Control-Request-Method", "POST"))

    def test_seg_008_origen_permitido_recibe_el_permiso(self):
        r = self.responder(self.lista(), "GET", "/api", ("Origin", "https://app.pe"))
        self.assertEqual((r.estado, r.cuerpo), (200, b"ok"))
        self.assertEqual(cabeceras(r)["access-control-allow-origin"], "https://app.pe")

    def test_seg_009_lo_que_depende_del_origen_lleva_vary(self):
        for origen in ("https://app.pe", "https://malo.pe"):
            with self.subTest(origen=origen):
                r = self.responder(self.lista(), "GET", "/api", ("Origin", origen))
                self.assertIn("Origin", cabeceras(r)["vary"])
                r = self.preflight(self.lista(), origen)
                self.assertIn("Origin", cabeceras(r)["vary"])

    def test_seg_009_vary_se_suma_al_de_la_accion(self):
        r = self.responder(self.lista(), "GET", "/variada", ("Origin", "https://app.pe"))
        self.assertEqual(nombres(r).count("vary"), 1)
        self.assertEqual(cabeceras(r)["vary"], "Accept-Encoding, Origin")

    def test_seg_010_origen_ajeno_sin_permiso_pero_no_bloqueado(self):
        r = self.responder(self.lista(credenciales=True), "GET", "/api",
                           ("Origin", "https://malo.pe"))
        self.assertEqual((r.estado, r.cuerpo, self.llamadas), (200, b"ok", 1))
        self.assertEqual(de_cors(r), {})

    def test_seg_011_sin_origin_ninguna_cabecera_cors(self):
        for cors in (self.lista(credenciales=True), Cors("*"), None):
            with self.subTest(cors=cors):
                r = self.responder(cors, "GET", "/api")
                self.assertEqual((r.estado, de_cors(r)), (200, {}))

    def test_seg_012_preflight_admitido_204_con_metodos_cabeceras_y_cache(self):
        r = self.preflight(self.lista(), "https://app.pe")
        c = cabeceras(r)
        self.assertEqual((r.estado, self.llamadas), (204, 0))
        self.assertEqual(c["access-control-allow-origin"], "https://app.pe")
        self.assertIn("POST", c["access-control-allow-methods"])
        self.assertIn("Content-Type", c["access-control-allow-headers"])
        self.assertGreater(int(c["access-control-max-age"]), 0)

    def test_seg_013_preflight_ajeno_403(self):
        r = self.preflight(self.lista(), "https://malo.pe")
        self.assertEqual((r.estado, self.llamadas), (403, 0))
        self.assertEqual(de_cors(r), {})

    def test_seg_013_sin_cors_configurado_el_preflight_no_recibe_permiso(self):
        r = self.preflight(None, "https://app.pe")
        self.assertNotEqual(r.estado, 204)
        self.assertEqual(de_cors(r), {})

    def test_seg_014_comodin_sin_credenciales_responde_comodin(self):
        r = self.responder(Cors("*"), "GET", "/api", ("Origin", "https://cualquiera.pe"))
        self.assertEqual(de_cors(r), {"access-control-allow-origin": "*"})

    def test_seg_014_con_credenciales_devuelve_el_origen_concreto(self):
        r = self.responder(self.lista(credenciales=True), "GET", "/api",
                           ("Origin", "https://app.pe"))
        self.assertEqual(de_cors(r), {"access-control-allow-origin": "https://app.pe",
                                      "access-control-allow-credentials": "true"})

    def test_seg_014_comodin_con_credenciales_no_se_construye(self):
        for origenes in ("*", ["*"], ["https://app.pe", "*"]):
            with self.subTest(origenes=origenes):
                with self.assertRaises(ValueError):
                    Cors(origenes, credenciales=True)

    def test_options_sin_access_control_request_method_no_es_preflight(self):
        r = self.responder(self.lista(), "OPTIONS", "/api", ("Origin", "https://app.pe"))
        self.assertEqual(r.estado, 405)


def galleta(respuesta):
    valor = cabeceras(respuesta).get("set-cookie", "")
    return valor.split(";", 1)[0] if valor else None


class CsrfTests(unittest.TestCase):

    def setUp(self):
        self.hechas = []
        r = Router()
        r.agregar("GET", "/token", lambda p: p.token_csrf())
        r.agregar("GET", "/api", lambda p: "ok")
        r.agregar("POST", "/entrar", self.entrar)
        for verbo in ("POST", "PUT", "PATCH", "DELETE"):
            r.agregar(verbo, "/api", self.escribir)
        for camino in ("/api/publico", "/api/publico/*", "/api/publicoSECRETO"):
            r.agregar("POST", camino, self.escribir)
        self.servidor = Servidor(r, csrf=Csrf(exentas=["/api/publico"]))

    def escribir(self, p):
        self.hechas.append((p.verbo, p.camino))
        return "hecho"

    def entrar(self, p):
        p.sesion().rotar()
        return p.token_csrf()

    def pedir(self, verbo, camino, *cab, cuerpo=b""):
        return self.servidor.responder(peticion(verbo, camino, *cab, cuerpo=cuerpo))

    def token(self):
        r = self.pedir("GET", "/token")
        return galleta(r), r.cuerpo.decode()

    def test_seg_015_metodo_seguro_pasa_sin_token(self):
        for verbo in ("GET", "HEAD"):
            with self.subTest(verbo=verbo):
                self.assertEqual(self.pedir(verbo, "/api").estado, 200)

    def test_seg_016_metodo_que_escribe_sin_token_403(self):
        for verbo in ("POST", "PUT", "PATCH", "DELETE"):
            with self.subTest(verbo=verbo):
                self.assertEqual(self.pedir(verbo, "/api").estado, 403)
        cookie, _ = self.token()
        self.assertEqual(self.pedir("POST", "/api", ("Cookie", cookie)).estado, 403)
        self.assertEqual(self.hechas, [])

    def test_seg_016_el_csrf_no_tapa_el_404_ni_el_405(self):
        self.assertEqual(self.pedir("POST", "/nada").estado, 404)
        self.assertEqual(self.pedir("POST", "/token").estado, 405)

    def test_seg_017_se_emite_un_token_atado_a_una_sesion(self):
        with mock.patch.object(seguridad.secrets, "token_urlsafe",
                               wraps=seguridad.secrets.token_urlsafe) as fuente:
            cookie, token = self.token()
        fuente.assert_called_once_with(32)
        self.assertGreaterEqual(len(token), 40)
        self.assertTrue(cookie.startswith("cero_sid="))
        id = cookie.split("=", 1)[1]
        self.assertEqual(self.servidor.sesiones.recuperar(id).leer("csrf"), token)
        otra = self.pedir("GET", "/token", ("Cookie", cookie))
        self.assertEqual(otra.cuerpo.decode(), token)

    def test_seg_018_token_valido_pasa(self):
        cookie, token = self.token()
        r = self.pedir("POST", "/api", ("Cookie", cookie), ("X-CSRF-Token", token))
        self.assertEqual((r.estado, self.hechas), (200, [("POST", "/api")]))

    def test_seg_018_token_en_un_formulario_pasa(self):
        cookie, token = self.token()
        r = self.pedir("POST", "/api", ("Cookie", cookie),
                       ("Content-Type", "application/x-www-form-urlencoded"),
                       cuerpo=f"nombre=ana&_csrf={token}".encode())
        self.assertEqual(r.estado, 200)

    def test_seg_018_token_erroneo_403(self):
        cookie, token = self.token()
        otra, ajeno = self.token()
        for cab in ((("Cookie", cookie), ("X-CSRF-Token", "token-inventado")),
                    (("Cookie", cookie), ("X-CSRF-Token", token[:-1])),
                    (("Cookie", cookie), ("X-CSRF-Token", ajeno)),
                    (("X-CSRF-Token", token),),
                    (("Cookie", cookie), ("X-CSRF-Token", token), ("X-CSRF-Token", token))):
            with self.subTest(cab=cab):
                self.assertEqual(self.pedir("POST", "/api", *cab).estado, 403)
        self.assertEqual(self.hechas, [])

    def test_seg_018_se_compara_en_tiempo_constante(self):
        cookie, token = self.token()
        with mock.patch.object(seguridad.hmac, "compare_digest",
                               wraps=seguridad.hmac.compare_digest) as comparar:
            self.pedir("POST", "/api", ("Cookie", cookie), ("X-CSRF-Token", token))
        comparar.assert_called_once()

    def test_seg_019_exencion_por_segmento_completo(self):
        self.assertEqual(self.pedir("POST", "/api/publico").estado, 200)
        self.assertEqual(self.pedir("POST", "/api/publico/").estado, 200)
        self.assertEqual(self.pedir("POST", "/api/publico/x").estado, 200)
        self.assertEqual(self.pedir("POST", "/api/publicoSECRETO").estado, 403)
        self.assertEqual(self.pedir("POST", "/api").estado, 403)

    def test_seg_019_la_barra_final_de_la_exencion_no_cambia_nada(self):
        self.servidor.csrf = Csrf(exentas=["/api/publico/"])
        self.assertEqual(self.pedir("POST", "/api/publico").estado, 200)
        self.assertEqual(self.pedir("POST", "/api/publicoSECRETO").estado, 403)

    def test_rotar_al_entrar_cambia_el_token(self):
        cookie, viejo = self.token()
        r = self.pedir("POST", "/entrar", ("Cookie", cookie), ("X-CSRF-Token", viejo))
        nueva, nuevo = galleta(r), r.cuerpo.decode()
        self.assertEqual(r.estado, 200)
        self.assertNotEqual(nuevo, viejo)
        self.assertEqual(self.pedir("POST", "/api", ("Cookie", nueva),
                                    ("X-CSRF-Token", viejo)).estado, 403)
        self.assertEqual(self.pedir("POST", "/api", ("Cookie", nueva),
                                    ("X-CSRF-Token", nuevo)).estado, 200)

    def test_sin_csrf_configurado_no_se_exige(self):
        self.servidor.csrf = None
        self.assertEqual(self.pedir("POST", "/api").estado, 200)


if __name__ == "__main__":
    unittest.main()
