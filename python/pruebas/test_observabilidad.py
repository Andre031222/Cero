import http.client
import json
import os
import re
import sys
import tempfile
import threading
import time
import unittest

from cero.observabilidad import Acceso, Metricas, Registro, Salud, interpolar
from cero.peticion import Peticion
from cero.respuesta import Respuesta
from cero.ruta import Router
from cero.servidor import Servidor

CLF = re.compile(r'^(\S+) - (\S+) \[(\d{2}/[A-Z][a-z]{2}/\d{4}:\d{2}:\d{2}:\d{2} [+-]\d{4})\] '
                 r'"((?:[^"\\]|\\.)*)" (\d{3}) (\d+|-)$')
MUESTRA = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})? (\S+)$')
ETIQUETA = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)",?')


def peticion(verbo, camino):
    return Peticion(verbo, camino, camino, "", "HTTP/1.1", [("host", "x")])


def leer_json(respuesta):
    return json.loads(respuesta.cuerpo)


def prometheus(texto):
    muestras, tipos = {}, {}
    for linea in texto.splitlines():
        if linea.startswith("# TYPE "):
            _, _, nombre, tipo = linea.split(" ")
            tipos[nombre] = tipo
            continue
        if linea.startswith("#") or not linea:
            continue
        nombre, etiquetas, valor = MUESTRA.match(linea).groups()
        pares = ETIQUETA.findall(etiquetas or "")
        if etiquetas:
            assert "".join(f'{k}="{v}",' for k, v in pares).rstrip(",") == etiquetas, linea
        muestras[(nombre, frozenset(pares))] = float(valor)
    return muestras, tipos


def serie(muestras, nombre, **etiquetas):
    return muestras.get((nombre, frozenset(etiquetas.items())))


class Salud_(unittest.TestCase):

    def setUp(self):
        self.estado = {"bd": True}
        self.llamadas = []
        self.salud = Salud()
        self.salud.comprobacion("bd", self.bd)
        self.salud.comprobacion("disco", lambda: True)

    def bd(self):
        self.llamadas.append("bd")
        if self.estado["bd"] == "lanza":
            raise ConnectionError("jdbc://10.0.0.7:5432/interna")
        return self.estado["bd"]

    def test_obs_001_vida_responde_200_y_cuenta_el_tiempo_en_pie(self):
        primera = self.salud.vivo(peticion("GET", "/salud/vida"))
        time.sleep(0.02)
        segunda = self.salud.vivo(peticion("GET", "/salud/vida"))
        self.assertEqual((primera.estado, segunda.estado), (200, 200))
        antes, despues = leer_json(primera)["activo_ms"], leer_json(segunda)["activo_ms"]
        self.assertGreaterEqual(antes, 0)
        self.assertGreaterEqual(despues - antes, 15)

    def test_obs_002_lista_responde_200_y_nombra_cada_comprobacion(self):
        respuesta = self.salud.listo(peticion("GET", "/salud/lista"))
        self.assertEqual(respuesta.estado, 200)
        cuerpo = leer_json(respuesta)
        self.assertEqual(cuerpo["estado"], "listo")
        self.assertEqual(cuerpo["comprobaciones"], {"bd": "bien", "disco": "bien"})

    def test_obs_003_una_comprobacion_caida_da_503_y_la_vida_sigue_en_200(self):
        self.estado["bd"] = False
        self.assertEqual(self.salud.listo(peticion("GET", "/salud/lista")).estado, 503)
        self.llamadas.clear()
        self.estado["bd"] = "lanza"
        self.assertEqual(self.salud.vivo(peticion("GET", "/salud/vida")).estado, 200)
        self.assertEqual(self.llamadas, [])

    def test_obs_004_el_503_dice_cual_fallo_sin_ocultar_las_que_van(self):
        self.estado["bd"] = False
        cuerpo = leer_json(self.salud.listo(peticion("GET", "/salud/lista")))
        self.assertEqual(cuerpo["estado"], "no listo")
        self.assertTrue(cuerpo["comprobaciones"]["bd"].startswith("mal"))
        self.assertEqual(cuerpo["comprobaciones"]["disco"], "bien")

    def test_obs_005_una_comprobacion_que_lanza_da_503_y_no_500(self):
        self.estado["bd"] = "lanza"
        router = Router()
        router.agregar("GET", "/salud/lista", self.salud.listo)
        respuesta = Servidor(router, registro=Registro(lambda linea: None)).responder(
            peticion("GET", "/salud/lista"))
        self.assertEqual(respuesta.estado, 503)
        cuerpo = leer_json(respuesta)
        self.assertIn("ConnectionError", cuerpo["comprobaciones"]["bd"])
        self.assertIn("10.0.0.7", cuerpo["comprobaciones"]["bd"])
        self.assertEqual(cuerpo["comprobaciones"]["disco"], "bien")

    def test_obs_006_en_publico_el_503_no_revela_ni_mensaje_ni_nombres_ni_lista(self):
        salud = Salud(publico=True)
        salud.comprobacion("bd", self.bd)
        for estado in (False, "lanza"):
            with self.subTest(estado=estado):
                self.estado["bd"] = estado
                respuesta = salud.listo(peticion("GET", "/salud/lista"))
                self.assertEqual(respuesta.estado, 503)
                self.assertEqual(leer_json(respuesta), {"estado": "no listo"})
                for rastro in (b"bd", b"10.0.0.7", b"ConnectionError", b"comprobaciones"):
                    self.assertNotIn(rastro, respuesta.cuerpo)
        self.assertEqual(salud.vivo(peticion("GET", "/salud/vida")).estado, 200)

    def test_obs_007_en_publico_y_en_verde_solo_dice_listo(self):
        salud = Salud(publico=True)
        salud.comprobacion("bd", self.bd)
        respuesta = salud.listo(peticion("GET", "/salud/lista"))
        self.assertEqual(respuesta.estado, 200)
        self.assertEqual(leer_json(respuesta), {"estado": "listo"})

    def test_obs_005_una_comprobacion_colgada_da_503_dentro_del_plazo(self):
        suelta = threading.Event()
        self.addCleanup(suelta.set)
        llamadas = []

        def colgada():
            llamadas.append(1)
            suelta.wait(10)
            return True
        salud = Salud(plazo=0.2)
        salud.comprobacion("colgada", colgada)
        salud.comprobacion("disco", lambda: True)
        for _ in range(3):
            inicio = time.monotonic()
            respuesta = salud.listo(peticion("GET", "/salud/lista"))
            self.assertLess(time.monotonic() - inicio, 1)
            self.assertEqual(respuesta.estado, 503)
            cuerpo = leer_json(respuesta)
            self.assertIn("plazo", cuerpo["comprobaciones"]["colgada"])
            self.assertEqual(cuerpo["comprobaciones"]["disco"], "bien")
        self.assertEqual(len(llamadas), 1)
        inicio = time.monotonic()
        self.assertEqual(salud.vivo(peticion("GET", "/salud/vida")).estado, 200)
        self.assertLess(time.monotonic() - inicio, 0.05)
        suelta.set()
        time.sleep(0.05)
        self.assertEqual(salud.listo(peticion("GET", "/salud/lista")).estado, 200)

    def test_las_comprobaciones_corren_a_la_vez(self):
        salud = Salud(plazo=1)
        for nombre in ("a", "b", "c", "d"):
            salud.comprobacion(nombre, lambda: time.sleep(0.3) or True)
        inicio = time.monotonic()
        self.assertEqual(salud.listo(None).estado, 200)
        self.assertLess(time.monotonic() - inicio, 0.9)

    def test_la_salud_va_en_json(self):
        for respuesta in (self.salud.vivo(None), self.salud.listo(None)):
            self.assertIn(("Content-Type", "application/json"), respuesta.cabeceras)


class Registro_(unittest.TestCase):

    def setUp(self):
        self.lineas = []

    def registro(self, nivel):
        return Registro(self.lineas.append, nivel)

    def test_obs_008_la_linea_lleva_nivel_origen_y_valores(self):
        self.registro("INFO").escribir("AVISO", "conforme", "valor={} y {}", 42, "otro")
        self.assertEqual(len(self.lineas), 1)
        linea = self.lineas[0]
        self.assertIn(" AVISO ", linea)
        self.assertIn(" conforme ", linea)
        self.assertTrue(linea.endswith("valor=42 y otro"), linea)

    def test_obs_009_el_nivel_filtra_por_debajo_y_deja_pasar_por_encima(self):
        registro = self.registro("AVISO")
        for nivel in ("TRAZA", "DEPURA", "INFO"):
            registro.escribir(nivel, "x", "no {}", nivel)
        self.assertEqual(self.lineas, [])
        registro.escribir("AVISO", "x", "sí")
        registro.escribir("ERROR", "x", "también")
        self.assertEqual(len(self.lineas), 2)

    def test_obs_009_hay_un_nivel_que_calla_todo(self):
        registro = self.registro("NADA")
        for nivel in ("TRAZA", "DEPURA", "INFO", "AVISO", "ERROR"):
            registro.escribir(nivel, "x", "silencio")
        self.assertEqual(self.lineas, [])

    def test_un_nivel_desconocido_no_se_admite(self):
        with self.assertRaises(ValueError):
            Registro(self.lineas.append, "WARN")
        with self.assertRaises(ValueError):
            self.registro("INFO").escribir("WARN", "x", "y")

    def test_obs_010_un_error_con_excepcion_lleva_tipo_y_mensaje(self):
        try:
            raise ConnectionRefusedError("sin ruta al host")
        except ConnectionRefusedError as fallo:
            self.registro("INFO").escribir("ERROR", "x", "falló al conectar", excepcion=fallo)
        self.assertIn("ConnectionRefusedError", self.lineas[0])
        self.assertIn("sin ruta al host", self.lineas[0])
        self.assertIn("falló al conectar", self.lineas[0])

    def test_obs_010_el_servidor_registra_la_excepcion_no_controlada(self):
        def falla(peticion):
            raise LookupError("SECRETO-7f3")
        router = Router()
        router.agregar("GET", "/falla", falla)
        respuesta = Servidor(router, registro=self.registro("INFO")).responder(
            peticion("GET", "/falla"))
        self.assertEqual(respuesta.estado, 500)
        self.assertNotIn(b"SECRETO", respuesta.cuerpo)
        self.assertEqual(len(self.lineas), 1)
        self.assertIn(" ERROR ", self.lineas[0])
        self.assertIn("LookupError", self.lineas[0])
        self.assertIn("SECRETO-7f3", self.lineas[0])

    def test_obs_011_con_valores_de_menos_conserva_el_marcador(self):
        self.assertEqual(interpolar("a {} b {}", 1), "a 1 b {}")
        self.assertEqual(interpolar("{}"), "{}")

    def test_obs_011_con_valores_de_mas_los_ignora(self):
        self.assertEqual(interpolar("a {}", 1, 2), "a 1")
        self.assertEqual(interpolar("sin nada", 1), "sin nada")

    def test_obs_011_interpolar_no_lanza_nunca(self):
        class Roto:
            def __str__(self):
                raise RuntimeError("no se deja")

            def __repr__(self):
                raise RuntimeError("tampoco")
        self.assertIn("Roto", interpolar("a {}", Roto()))
        self.assertIsInstance(interpolar(Roto(), 1), str)
        self.assertIsInstance(interpolar(None), str)
        self.registro("TRAZA").escribir("INFO", Roto(), Roto(), Roto(), excepcion=Roto())
        self.assertEqual(len(self.lineas), 1)

    def test_un_valor_con_marcador_no_se_vuelve_a_interpolar(self):
        self.assertEqual(interpolar("{} y {}", "{}", 2), "{} y 2")

    def test_un_salto_de_linea_no_parte_la_linea(self):
        self.registro("INFO").escribir("INFO", "x", "valor {}", "a\nERROR falso\r")
        self.assertEqual(len(self.lineas), 1)
        self.assertNotIn("\n", self.lineas[0])
        self.assertNotIn("\r", self.lineas[0])

    def test_un_destino_que_falla_no_rompe_nada(self):
        def roto(linea):
            raise OSError(28, "No queda espacio en el dispositivo")
        Registro(roto, "INFO").escribir("ERROR", "x", "y")
        router = Router()
        router.agregar("GET", "/falla", lambda p: 1 / 0)
        respuesta = Servidor(router, registro=Registro(roto)).responder(peticion("GET", "/falla"))
        self.assertEqual(respuesta.estado, 500)

    def test_los_caracteres_de_control_no_llegan_a_la_terminal(self):
        self.registro("INFO").escribir("INFO", "x", "valor {}", "a\x1b[2Jb\x07")
        self.assertNotRegex(self.lineas[0], "[\x00-\x1f\x7f]")
        self.assertIn("\\x1b", self.lineas[0])

    def test_el_archivo_se_escribe_linea_a_linea(self):
        with tempfile.TemporaryDirectory() as carpeta:
            camino = os.path.join(carpeta, "log")
            registro = Registro(camino, "INFO")
            registro.escribir("INFO", "x", "uno")
            with open(camino, encoding="utf-8") as archivo:
                self.assertTrue(archivo.read().endswith(" INFO x uno\n"))
            registro.cerrar()


def router_de_tienda():
    router = Router()
    router.agregar("GET", "/tienda", lambda p: "catálogo")
    router.agregar("GET", "/tienda/{id}", ver)
    router.agregar("GET", "/lento", lambda p: time.sleep(int(p.consulta.split("=")[1]) / 1000))
    router.agregar("GET", "/roto", lambda p: 1 / 0)
    router.agregar("GET", "/vista", lambda p: Respuesta(200, [("X-Rota", "a\nb")], b"x"))
    router.agregar("GET", "/salud", lambda p: "ok")
    router.agregar("GET", "/salud/vida", lambda p: "ok")
    router.agregar("GET", "/yo", identificado)
    return router


def ver(peticion):
    if int(peticion.variables["id"]) > 100:
        return Respuesta(404, [], b"no existe")
    return "artículo " + peticion.variables["id"]


def identificado(peticion):
    peticion.usuario = "ana maría"
    return "hola"


class ConServidor(unittest.TestCase):

    def arrancar(self, **ajustes):
        self.lineas = []
        ajustes.setdefault("registro", Registro(lambda linea: None))
        self.servidor = Servidor(router_de_tienda(), **ajustes)
        self.servidor.router.agregar("GET", "/metricas", lambda p: self.servidor.metricas.exponer(p))
        hilo = threading.Thread(target=self.servidor.escuchar, args=(0,), daemon=True)
        hilo.start()
        self.servidor.listo.wait(5)
        self.addCleanup(hilo.join, 5)
        self.addCleanup(self.servidor.cerrar)

    def pedir(self, camino, verbo="GET"):
        conexion = http.client.HTTPConnection("127.0.0.1", self.servidor.puerto, timeout=5)
        try:
            conexion.request(verbo, camino)
            respuesta = conexion.getresponse()
            return respuesta.status, respuesta.read(), respuesta
        finally:
            conexion.close()


class Metricas_(ConServidor):

    def setUp(self):
        self.arrancar(metricas=Metricas(ignoradas=["/salud", "/metricas"]))

    def leer(self):
        estado, cuerpo, _ = self.pedir("/metricas")
        self.assertEqual(estado, 200)
        return prometheus(cuerpo.decode())[0]

    def test_obs_013_cuenta_todas_y_aparte_las_que_fallan(self):
        for camino in ("/tienda", "/tienda", "/tienda/7", "/tienda/999", "/roto", "/nada"):
            self.pedir(camino)
        muestras = self.leer()
        self.assertEqual(serie(muestras, "cero_all_requests_total"), 6)
        self.assertEqual(serie(muestras, "cero_all_request_errors_total"), 3)
        self.assertEqual(serie(muestras, "cero_requests_total", ruta="/tienda"), 2)
        self.assertEqual(serie(muestras, "cero_request_errors_total", ruta="/tienda"), 0)
        self.assertEqual(serie(muestras, "cero_request_errors_total", ruta="/roto"), 1)

    def test_obs_014_agrupa_por_patron_y_no_por_url(self):
        for n in range(1, 4):
            self.pedir(f"/tienda/{n}")
            self.pedir(f"/no/existe/{n}")
        muestras = self.leer()
        self.assertEqual(serie(muestras, "cero_requests_total", ruta="/tienda/{id}"), 3)
        self.assertEqual(serie(muestras, "cero_requests_total", ruta="(sin ruta)"), 3)
        rutas = {dict(e)["ruta"] for (nombre, e) in muestras if nombre == "cero_requests_total"}
        self.assertEqual(rutas, {"/tienda/{id}", "(sin ruta)"})

    def test_obs_015_informa_percentiles_y_no_solo_la_media(self):
        for _ in range(19):
            self.pedir("/lento?ms=1")
        self.pedir("/lento?ms=300")
        muestras = self.leer()
        p50 = serie(muestras, "cero_request_duration_ms", ruta="/lento", quantile="0.5")
        p95 = serie(muestras, "cero_request_duration_ms", ruta="/lento", quantile="0.95")
        p99 = serie(muestras, "cero_request_duration_ms", ruta="/lento", quantile="0.99")
        self.assertLess(p50, 100)
        self.assertLess(p95, 100)
        self.assertGreaterEqual(p99, 250)
        self.assertEqual(serie(muestras, "cero_request_duration_ms_count", ruta="/lento"), 20)
        self.assertGreaterEqual(serie(muestras, "cero_request_duration_ms_sum", ruta="/lento"), 300)

    def test_obs_016_un_404_cuenta_como_error(self):
        self.pedir("/tienda/999")
        self.pedir("/nada")
        muestras = self.leer()
        self.assertEqual(serie(muestras, "cero_request_errors_total", ruta="/tienda/{id}"), 1)
        self.assertEqual(serie(muestras, "cero_request_errors_total", ruta="(sin ruta)"), 1)

    def test_obs_017_las_rutas_ignoradas_no_se_cuentan(self):
        self.pedir("/salud")
        self.pedir("/salud/vida")
        self.pedir("/metricas")
        self.pedir("/tienda")
        muestras = self.leer()
        self.assertEqual(serie(muestras, "cero_all_requests_total"), 1)
        rutas = {dict(e).get("ruta") for (nombre, e) in muestras}
        self.assertFalse({r for r in rutas if r and (r.startswith("/salud") or r == "/metricas")})

    def test_obs_018_exposicion_prometheus_con_total_y_detalle_por_ruta(self):
        self.pedir("/tienda")
        estado, cuerpo, respuesta = self.pedir("/metricas")
        self.assertEqual(estado, 200)
        self.assertEqual(respuesta.getheader("Content-Type"),
                         "text/plain; version=0.0.4; charset=utf-8")
        muestras, tipos = prometheus(cuerpo.decode())
        self.assertEqual(tipos, {"cero_requests_total": "counter",
                                 "cero_request_errors_total": "counter",
                                 "cero_all_requests_total": "counter",
                                 "cero_all_request_errors_total": "counter",
                                 "cero_request_duration_ms": "summary",
                                 "cero_uptime_ms": "gauge"})
        self.assertEqual(serie(muestras, "cero_all_requests_total"), 1)
        self.assertEqual(serie(muestras, "cero_requests_total", ruta="/tienda"), 1)
        self.assertTrue(cuerpo.endswith(b"\n"))

    def test_los_cuantiles_salen_de_una_ventana_acotada(self):
        metricas = Metricas()
        for _ in range(100):
            metricas.anotar("/a", "/a", 200, 1000.0)
        for _ in range(1024):
            metricas.anotar("/a", "/a", 200, 1.0)
        muestras, _ = prometheus(metricas.exponer(None).cuerpo.decode())
        self.assertEqual(serie(muestras, "cero_request_duration_ms", ruta="/a", quantile="0.99"), 1)
        self.assertEqual(serie(muestras, "cero_request_duration_ms_count", ruta="/a"), 1124)

    def test_el_cuantil_es_el_de_rango_mas_cercano(self):
        metricas = Metricas()
        for valor in range(1, 101):
            metricas.anotar("/a", "/a", 200, float(valor))
        muestras, _ = prometheus(metricas.exponer(None).cuerpo.decode())
        for q, esperado in (("0.5", 50), ("0.95", 95), ("0.99", 99)):
            self.assertEqual(serie(muestras, "cero_request_duration_ms", ruta="/a", quantile=q),
                             esperado)

    def test_anotar_y_exponer_a_la_vez_no_pierde_cuentas(self):
        previo = sys.getswitchinterval()
        sys.setswitchinterval(1e-6)
        self.addCleanup(sys.setswitchinterval, previo)
        metricas, fallos = Metricas(), []

        def anotar(n):
            try:
                for i in range(3000):
                    metricas.anotar(f"/r{(i + n) % 7}", "/r", 500, 1.0)
            except Exception as fallo:
                fallos.append(fallo)

        def exponer():
            try:
                for _ in range(200):
                    metricas.exponer(None)
            except Exception as fallo:
                fallos.append(fallo)
        hilos = [threading.Thread(target=anotar, args=(n,)) for n in range(8)]
        hilos += [threading.Thread(target=exponer) for _ in range(2)]
        for hilo in hilos:
            hilo.start()
        for hilo in hilos:
            hilo.join()
        self.assertEqual(fallos, [])
        muestras, _ = prometheus(metricas.exponer(None).cuerpo.decode())
        self.assertEqual(serie(muestras, "cero_all_requests_total"), 24000)
        self.assertEqual(serie(muestras, "cero_all_request_errors_total"), 24000)

    def test_la_etiqueta_se_escapa(self):
        metricas = Metricas()
        metricas.anotar('/a"b\\c\nd', "/x", 200, 1.0)
        texto = metricas.exponer(None).cuerpo.decode()
        self.assertIn('cero_requests_total{ruta="/a\\"b\\\\c\\nd"} 1', texto)
        prometheus(texto)


class Acceso_(ConServidor):

    def setUp(self):
        self.arrancar(acceso=Acceso(self.anotar, ignoradas=["/salud"]),
                      metricas=Metricas())

    def anotar(self, linea):
        self.lineas.append(linea)

    def una(self, camino):
        self.lineas.clear()
        estado, _, _ = self.pedir(camino)
        self.assertEqual(len(self.lineas), 1, self.lineas)
        partes = CLF.match(self.lineas[0])
        self.assertIsNotNone(partes, self.lineas[0])
        return estado, partes.groups()

    def test_obs_019_una_linea_por_peticion_con_verbo_ruta_y_estado(self):
        estado, (cliente, _, _, peticion, codigo, octetos) = self.una("/tienda")
        self.assertEqual(cliente, "127.0.0.1")
        self.assertEqual(peticion, "GET /tienda HTTP/1.1")
        self.assertEqual((estado, codigo), (200, "200"))
        self.assertEqual(octetos, str(len("catálogo".encode())))

    def test_obs_020_la_ruta_conserva_la_consulta(self):
        _, (_, _, _, peticion, _, _) = self.una("/tienda?buscar=zapato&x=1")
        self.assertEqual(peticion, "GET /tienda?buscar=zapato&x=1 HTTP/1.1")

    def test_obs_021_el_usuario_anonimo_es_un_guion(self):
        _, (_, usuario, _, _, _, _) = self.una("/tienda")
        self.assertEqual(usuario, "-")
        self.assertIn(" - - [", self.lineas[0])

    def test_el_usuario_identificado_sale_escapado(self):
        _, (_, usuario, _, _, _, _) = self.una("/yo")
        self.assertEqual(usuario, "ana\\x20mar\\xc3\\xada")

    def test_obs_022_registra_los_errores_y_las_excepciones(self):
        self.assertEqual(self.una("/tienda/999")[1][4], "404")
        self.assertEqual(self.una("/nada")[1][4], "404")
        self.assertEqual(self.una("/roto")[1][4], "500")

    def test_obs_023_las_rutas_ignoradas_no_se_registran(self):
        self.lineas.clear()
        self.pedir("/salud")
        self.pedir("/salud/vida")
        self.pedir("/saludable")
        self.assertEqual(len(self.lineas), 1)
        self.assertIn('"GET /saludable HTTP/1.1" 404', self.lineas[0])

    def test_obs_012_un_fallo_al_construir_la_respuesta_se_registra_con_el_estado_enviado(self):
        estado, (_, _, _, _, codigo, _) = self.una("/vista")
        self.assertEqual((estado, codigo), (500, "500"))
        muestras, _ = prometheus(self.servidor.metricas.exponer(None).cuerpo.decode())
        self.assertEqual(serie(muestras, "cero_request_errors_total", ruta="/vista"), 1)

    def test_head_no_cuenta_octetos(self):
        self.lineas.clear()
        self.pedir("/tienda", "HEAD")
        self.assertTrue(self.lineas[0].endswith('"HEAD /tienda HTTP/1.1" 200 -'), self.lineas)

    def test_un_rechazo_con_la_peticion_leida_tambien_se_registra(self):
        self.lineas.clear()
        conexion = http.client.HTTPConnection("127.0.0.1", self.servidor.puerto, timeout=5)
        conexion.request("POST", "/tienda?x=1", body=b"x" * 10, headers={"Expect": "algo"})
        self.assertEqual(conexion.getresponse().status, 417)
        conexion.close()
        self.assertEqual(len(self.lineas), 1)
        self.assertIn('"POST /tienda?x=1 HTTP/1.1" 417', self.lineas[0])

    def test_un_destino_que_falla_no_rompe_la_respuesta(self):
        def roto(linea):
            raise OSError(28, "No queda espacio en el dispositivo")
        self.servidor.acceso = Acceso(roto)
        for _ in range(2):
            estado, cuerpo, _ = self.pedir("/tienda")
            self.assertEqual((estado, cuerpo), (200, "catálogo".encode()))

    def test_un_403_de_csrf_cuenta_en_su_ruta(self):
        from cero.seguridad import Csrf
        self.servidor.csrf = Csrf()
        self.servidor.router.agregar("POST", "/tienda", lambda p: "comprado")
        self.lineas.clear()
        estado, _, _ = self.pedir("/tienda", "POST")
        self.assertEqual(estado, 403)
        muestras, _ = prometheus(self.servidor.metricas.exponer(None).cuerpo.decode())
        self.assertEqual(serie(muestras, "cero_request_errors_total", ruta="/tienda"), 1)

    def test_las_comillas_del_destino_no_rompen_la_linea(self):
        _, (_, _, _, peticion, _, _) = self.una('/tienda?q="x"\\')
        self.assertEqual(peticion, 'GET /tienda?q=\\"x\\"\\\\ HTTP/1.1')

    def test_sin_datos_personales_quita_la_consulta_y_el_usuario(self):
        lineas = []
        acceso = Acceso(lineas.append, personales=False)
        consulta = Peticion("GET", "/yo?dni=12345678", "/yo", "dni=12345678", "HTTP/1.1", [])
        consulta.cliente, consulta.usuario = "10.0.0.1", "ana"
        acceso.anotar(consulta, 200, 4)
        self.assertNotIn("12345678", lineas[0])
        self.assertNotIn("ana", lineas[0])
        self.assertRegex(lineas[0], r'^10\.0\.0\.1 - - \[.*\] "GET /yo HTTP/1\.1" 200 4$')

    def test_el_archivo_se_escribe_linea_a_linea(self):
        with tempfile.TemporaryDirectory() as carpeta:
            camino = os.path.join(carpeta, "acceso")
            acceso = Acceso(camino)
            consulta = peticion("GET", "/x")
            consulta.cliente = "127.0.0.1"
            acceso.anotar(consulta, 200, 0)
            with open(camino, encoding="utf-8") as archivo:
                self.assertRegex(archivo.read(), r'^127\.0\.0\.1 - - \[.*\] "GET /x HTTP/1\.1" 200 -\n$')
            acceso.cerrar()


if __name__ == "__main__":
    unittest.main()
