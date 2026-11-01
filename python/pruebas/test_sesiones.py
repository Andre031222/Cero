import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from cero import sesion
from cero.peticion import Peticion
from cero.respuesta import Respuesta
from cero.ruta import Router
from cero.servidor import Servidor
from cero.sesion import Memoria, SesionInvalidada, Tabla
from pruebas.test_servidor import pedir as pedir_por_socket


class CicloDeVida:

    def almacen(self):
        raise NotImplementedError

    def test_ses_001_sin_identificador_no_recupera_nada(self):
        a = self.almacen()
        a.crear().poner("quien", "ana")
        self.assertIsNone(a.recuperar(None))
        self.assertIsNone(a.recuperar(""))
        self.assertIsNone(a.recuperar("f" * 64))

    def test_ses_002_identificador_largo_y_de_secrets(self):
        a = self.almacen()
        self.assertGreaterEqual(len(a.crear().id), 40)
        with mock.patch.object(sesion.secrets, "token_hex", return_value="a" * 64) as fuente:
            self.assertEqual(a.crear().id, "a" * 64)
        fuente.assert_called_once_with(32)

    def test_ses_003_dos_sesiones_no_comparten_identificador(self):
        a = self.almacen()
        ids = {a.crear().id for _ in range(200)}
        self.assertEqual(len(ids), 200)

    def test_ses_003_una_colision_se_vuelve_a_sortear(self):
        a = self.almacen()
        with mock.patch.object(sesion.secrets, "token_hex",
                               side_effect=["a" * 64, "a" * 64, "b" * 64]):
            primera, segunda = a.crear(), a.crear()
        self.assertEqual((primera.id, segunda.id), ("a" * 64, "b" * 64))

    def test_ses_004_invalidada_no_se_lee_ni_se_escribe(self):
        a = self.almacen()
        s = a.crear()
        s.poner("quien", "ana")
        s.invalidar()
        with self.assertRaises(SesionInvalidada):
            s.leer("quien")
        with self.assertRaises(SesionInvalidada):
            s.poner("quien", "otro")
        with self.assertRaises(SesionInvalidada):
            s.quitar("quien")

    def test_ses_004_invalidada_no_renace_en_el_almacen(self):
        a = self.almacen()
        s = a.crear()
        s.poner("quien", "ana")
        a.guardar(s)
        id = s.id
        s.invalidar()
        a.guardar(s)
        self.assertIsNone(a.recuperar(id))

    def test_ses_004_otra_vista_de_la_invalidada_no_la_resucita(self):
        a = self.almacen()
        s = a.crear()
        otra = a.recuperar(s.id)
        s.invalidar()
        otra.poner("quien", "ana")
        a.guardar(otra)
        self.assertIsNone(a.recuperar(s.id))

    def test_ses_005_rotar_cambia_el_id_y_conserva_los_atributos(self):
        a = self.almacen()
        s = a.crear()
        s.poner("quien", "ana")
        a.guardar(s)
        s.cookie_pendiente()
        viejo = s.id
        nuevo = s.rotar()
        self.assertNotEqual(viejo, nuevo)
        self.assertEqual(s.id, nuevo)
        self.assertEqual(s.leer("quien"), "ana")
        self.assertEqual(s.cookie_pendiente(), nuevo)
        self.assertIsNone(s.cookie_pendiente())
        self.assertEqual(a.recuperar(nuevo).leer("quien"), "ana")
        self.assertIsNone(a.recuperar(viejo))

    def test_ses_006_una_invalidada_no_se_rota(self):
        a = self.almacen()
        s = a.crear()
        id = s.id
        s.invalidar()
        with self.assertRaises(SesionInvalidada):
            s.rotar()
        self.assertEqual(s.id, id)

    def test_ses_007_dos_peticiones_a_la_vez_no_se_pisan(self):
        a = self.almacen()
        id = a.crear().id
        barrera = threading.Barrier(16)

        def peticion(i):
            vista = a.recuperar(id)
            barrera.wait()
            vista.poner(f"k{i}", "v")
            a.guardar(vista)

        hilos = [threading.Thread(target=peticion, args=(i,)) for i in range(16)]
        for hilo in hilos:
            hilo.start()
        for hilo in hilos:
            hilo.join()
        final = a.recuperar(id)
        self.assertEqual([final.leer(f"k{i}") for i in range(16)], ["v"] * 16)

    def test_ses_007_quitar_tampoco_pisa_lo_ajeno(self):
        a = self.almacen()
        s = a.crear()
        s.poner("uno", "1")
        s.poner("dos", "2")
        a.guardar(s)
        x, y = a.recuperar(s.id), a.recuperar(s.id)
        x.quitar("uno")
        y.poner("tres", "3")
        a.guardar(x)
        a.guardar(y)
        final = a.recuperar(s.id)
        self.assertEqual((final.leer("uno"), final.leer("dos"), final.leer("tres")),
                         (None, "2", "3"))

    def test_caduca_por_inactividad(self):
        a = self.almacen()
        id = a.crear().id
        with mock.patch.object(sesion.time, "time", return_value=sesion.time.time() + 3600):
            self.assertIsNone(a.recuperar(id))
        self.assertIsNone(a.recuperar(id))

    def test_un_atributo_es_una_cadena(self):
        with self.assertRaises(TypeError):
            self.almacen().crear().poner("n", 1)


class EnMemoria(CicloDeVida, unittest.TestCase):

    def almacen(self):
        return Memoria()


def archivo_temporal(prueba):
    carpeta = tempfile.TemporaryDirectory()
    prueba.addCleanup(carpeta.cleanup)
    return str(Path(carpeta.name) / "sesiones.db")


class EnTabla(CicloDeVida, unittest.TestCase):

    def almacen(self):
        if not hasattr(self, "ruta"):
            self.ruta = archivo_temporal(self)
        return Tabla(self.ruta, "sesiones")


class Galleta(unittest.TestCase):

    def test_ses_009_banderas_de_la_cookie(self):
        llana = sesion.cabecera_cookie("abc", False)
        self.assertEqual(llana, "cero_sid=abc; Path=/; HttpOnly; SameSite=Lax")
        self.assertEqual(sesion.cabecera_cookie("abc", True), llana + "; Secure")

    def test_lee_el_identificador_de_las_cabeceras_cookie(self):
        id = "c" * 64
        self.assertEqual(sesion.id_de_cookie([f"a=1; cero_sid={id}; b=2"]), id)
        self.assertEqual(sesion.id_de_cookie(["a=1", f"cero_sid={id}"]), id)
        self.assertIsNone(sesion.id_de_cookie(["a=1"]))
        self.assertIsNone(sesion.id_de_cookie([]))
        self.assertIsNone(sesion.id_de_cookie(["cero_sid=../../x"]))
        self.assertIsNone(sesion.id_de_cookie(["xcero_sid=" + id]))


def router_de_sesiones():
    router = Router()
    router.agregar("GET", "/entrar", entrar)
    router.agregar("GET", "/quien", lambda p: p.sesion().leer("usuario") if p.sesion() else "nadie")
    router.agregar("GET", "/rotar", lambda p: p.sesion().rotar() and "rotada")
    router.agregar("GET", "/salir", lambda p: p.sesion().invalidar())
    router.agregar("GET", "/poner/{clave}", lambda p: p.sesion().poner(p.variables["clave"], "v"))
    router.agregar("GET", "/libre", lambda p: "sin sesión")
    router.agregar("GET", "/fija", lambda p: FIJA)
    return router


FIJA = Respuesta(200, [("X-Fija", "1")], b"fija")


def entrar(peticion):
    peticion.abrir_sesion().poner("usuario", "ana")
    return "dentro"


def pedir(servidor, camino, cookie=None, version="HTTP/1.1", segura=False):
    cabeceras = [("host", "x")] + ([("cookie", f"cero_sid={cookie}")] if cookie else [])
    p = Peticion("GET", camino, camino, "", version, cabeceras, segura=segura)
    return servidor.responder(p)


def galletas(respuesta):
    return [v for n, v in respuesta.cabeceras if n.lower() == "set-cookie"]


def id_emitido(respuesta):
    [galleta] = galletas(respuesta)
    return galleta.split(";")[0].removeprefix("cero_sid=")


class Emision(unittest.TestCase):

    def setUp(self):
        self.servidor = Servidor(router_de_sesiones())

    def test_ses_001_sin_cookie_no_hay_sesion_aunque_existan_otras(self):
        pedir(self.servidor, "/entrar")
        respuesta = pedir(self.servidor, "/quien")
        self.assertEqual(respuesta.cuerpo, b"nadie")
        self.assertEqual(galletas(respuesta), [])

    def test_ses_001_abrir_sin_cookie_crea_una_nueva(self):
        uno = id_emitido(pedir(self.servidor, "/entrar"))
        dos = id_emitido(pedir(self.servidor, "/entrar"))
        self.assertNotEqual(uno, dos)

    def test_ses_008_la_cookie_va_en_la_respuesta_que_crea_y_no_en_las_siguientes(self):
        id = id_emitido(pedir(self.servidor, "/entrar"))
        siguiente = pedir(self.servidor, "/quien", id)
        self.assertEqual(siguiente.cuerpo, b"ana")
        self.assertEqual(galletas(siguiente), [])
        self.assertEqual(galletas(pedir(self.servidor, "/entrar", id)), [])

    def test_ses_008_una_peticion_que_no_abre_sesion_no_lleva_cookie(self):
        self.assertEqual(galletas(pedir(self.servidor, "/libre")), [])

    def test_ses_008_la_cookie_no_ensucia_una_respuesta_compartida(self):
        self.servidor.router.agregar("GET", "/fija-y-sesion",
                                     lambda p: p.abrir_sesion() and FIJA)
        self.assertEqual(len(galletas(pedir(self.servidor, "/fija-y-sesion"))), 1)
        self.assertEqual(FIJA.cabeceras, [("X-Fija", "1")])

    def test_ses_009_httponly_samesite_y_secure_solo_con_tls(self):
        [llana] = galletas(pedir(self.servidor, "/entrar"))
        [segura] = galletas(pedir(self.servidor, "/entrar", segura=True))
        for galleta in (llana, segura):
            self.assertIn("; HttpOnly", galleta)
            self.assertIn("; SameSite=Lax", galleta)
        self.assertNotIn("Secure", llana)
        self.assertTrue(segura.endswith("; Secure"))

    def test_ses_004_tras_invalidar_la_cookie_vieja_no_recupera_nada(self):
        id = id_emitido(pedir(self.servidor, "/entrar"))
        self.assertEqual(pedir(self.servidor, "/salir", id).estado, 204)
        self.assertEqual(pedir(self.servidor, "/quien", id).cuerpo, b"nadie")

    def test_ses_005_rotar_reemite_la_cookie_una_vez(self):
        viejo = id_emitido(pedir(self.servidor, "/entrar"))
        nuevo = id_emitido(pedir(self.servidor, "/rotar", viejo))
        self.assertNotEqual(viejo, nuevo)
        despues = pedir(self.servidor, "/quien", nuevo)
        self.assertEqual((despues.cuerpo, galletas(despues)), (b"ana", []))
        self.assertEqual(pedir(self.servidor, "/quien", viejo).cuerpo, b"nadie")

    def test_ses_007_peticiones_simultaneas_por_el_pipeline(self):
        barrera = threading.Barrier(32)

        def a_la_vez(p):
            s = p.sesion()
            barrera.wait()
            s.poner(p.variables["clave"], "v")

        self.servidor.router.agregar("GET", "/a-la-vez/{clave}", a_la_vez)
        id = id_emitido(pedir(self.servidor, "/entrar"))
        hilos = [threading.Thread(target=pedir, args=(self.servidor, f"/a-la-vez/k{i}", id))
                 for i in range(32)]
        for hilo in hilos:
            hilo.start()
        for hilo in hilos:
            hilo.join()
        sesion_final = self.servidor.sesiones.recuperar(id)
        self.assertEqual([sesion_final.leer(f"k{i}") for i in range(32)], ["v"] * 32)
        self.assertEqual(sesion_final.leer("usuario"), "ana")

    def test_ses_010_la_cookie_se_emite_sea_cual_sea_la_version(self):
        for version in ("HTTP/1.0", "HTTP/1.1", "HTTP/2", "HTTP/3"):
            id = id_emitido(pedir(self.servidor, "/entrar", version=version))
            self.assertEqual(pedir(self.servidor, "/quien", id, version=version).cuerpo, b"ana")

    def test_ses_010_la_cookie_partida_en_varias_cabeceras_se_reconoce(self):
        id = id_emitido(pedir(self.servidor, "/entrar", version="HTTP/2"))
        cabeceras = [("host", "x"), ("cookie", "a=1"), ("cookie", f"cero_sid={id}"),
                     ("cookie", "b=2")]
        p = Peticion("GET", "/quien", "/quien", "", "HTTP/2", cabeceras)
        self.assertEqual(self.servidor.responder(p).cuerpo, b"ana")

    def test_ses_011_la_cookie_pendiente_se_consulta_una_vez_por_respuesta(self):
        original = sesion.Sesion.cookie_pendiente
        llamadas = []

        def contada(s):
            llamadas.append(s.id)
            return original(s)

        with mock.patch.object(sesion.Sesion, "cookie_pendiente", contada):
            id = id_emitido(pedir(self.servidor, "/entrar"))
            self.assertEqual(llamadas, [id])
            pedir(self.servidor, "/quien", id)
            self.assertEqual(llamadas, [id, id])
            pedir(self.servidor, "/libre", id)
            self.assertEqual(llamadas, [id, id])

    def test_ses_011_consultarla_la_consume(self):
        s = Memoria().crear()
        self.assertEqual(s.cookie_pendiente(), s.id)
        self.assertIsNone(s.cookie_pendiente())


class SobreElSocket(unittest.TestCase):

    def test_ses_008_la_cookie_viaja_por_http_1_1_y_vuelve(self):
        servidor = Servidor(router_de_sesiones())
        threading.Thread(target=servidor.escuchar, args=(0,), daemon=True).start()
        servidor.listo.wait(5)
        self.addCleanup(servidor.cerrar)
        crudo = b"GET /entrar HTTP/1.1\r\nHost: x\r\n\r\n"
        estado, cabeceras, _ = pedir_por_socket(servidor.puerto, crudo)
        self.assertEqual(estado, 200)
        galleta = cabeceras["set-cookie"]
        self.assertIn("; HttpOnly; SameSite=Lax", galleta)
        self.assertNotIn("Secure", galleta)
        par = galleta.split(";")[0]
        crudo = f"GET /quien HTTP/1.1\r\nHost: x\r\nCookie: {par}\r\n\r\n".encode()
        estado, cabeceras, cuerpo = pedir_por_socket(servidor.puerto, crudo)
        self.assertEqual((estado, cuerpo), (200, b"ana"))
        self.assertNotIn("set-cookie", cabeceras)


def sql(ruta, orden):
    conexion = sqlite3.connect(ruta)
    try:
        with conexion:
            return conexion.executescript(orden) if ";" in orden else conexion.execute(orden).fetchall()
    finally:
        conexion.close()


class Almacenamiento(unittest.TestCase):

    def setUp(self):
        self.ruta = archivo_temporal(self)

    def test_ses_012_una_sesion_abierta_en_una_instancia_se_reconoce_en_otra(self):
        una = Servidor(router_de_sesiones(), sesiones=Tabla(self.ruta, "sesiones"))
        otra = Servidor(router_de_sesiones(), sesiones=Tabla(self.ruta, "sesiones"))
        id = id_emitido(pedir(una, "/entrar"))
        respuesta = pedir(otra, "/quien", id)
        self.assertEqual((respuesta.cuerpo, galletas(respuesta)), (b"ana", []))
        nuevo = id_emitido(pedir(otra, "/rotar", id))
        self.assertEqual(pedir(una, "/quien", nuevo).cuerpo, b"ana")
        self.assertEqual(pedir(una, "/quien", id).cuerpo, b"nadie")
        pedir(una, "/salir", nuevo)
        self.assertEqual(pedir(otra, "/quien", nuevo).cuerpo, b"nadie")

    def test_ses_012_otro_proceso_la_reconoce(self):
        s = Tabla(self.ruta, "sesiones").crear()
        s.poner("usuario", "ana")
        s.almacen.guardar(s)
        raiz = str(Path(__file__).resolve().parent.parent)
        guion = ("import sys; sys.path.insert(0, sys.argv[1]); from cero.sesion import Tabla; "
                 "print(Tabla(sys.argv[2], 'sesiones').recuperar(sys.argv[3]).leer('usuario'))")
        salida = subprocess.run([sys.executable, "-I", "-c", guion, raiz, self.ruta, s.id],
                                capture_output=True, text=True, timeout=30, check=True)
        self.assertEqual(salida.stdout.strip(), "ana")

    def test_ses_013_el_nombre_de_la_tabla_no_tiene_valor_por_omision(self):
        with self.assertRaises(TypeError):
            Tabla(self.ruta)

    def test_ses_013_usa_la_tabla_que_se_le_nombra(self):
        Tabla(self.ruta, "cero_sesiones_app").crear()
        tablas = sql(self.ruta, "SELECT name FROM sqlite_master WHERE type = 'table'")
        self.assertEqual(tablas, [("cero_sesiones_app",)])

    def test_ses_013_dos_tablas_en_un_archivo_no_se_ven(self):
        id = Tabla(self.ruta, "una").crear().id
        self.assertIsNone(Tabla(self.ruta, "otra").recuperar(id))
        self.assertIsNotNone(Tabla(self.ruta, "una").recuperar(id))

    def test_ses_013_un_nombre_que_no_es_identificador_se_rechaza(self):
        for nombre in ("", "1tabla", 'x"; DROP TABLE y; --', "a b", "sqlite_master", "t" * 64):
            with self.assertRaises(ValueError, msg=nombre):
                Tabla(self.ruta, nombre)

    def test_una_peticion_que_no_cambia_nada_no_escribe(self):
        servidor = Servidor(router_de_sesiones(), sesiones=Tabla(self.ruta, "sesiones"))
        id = id_emitido(pedir(servidor, "/entrar"))
        sql(self.ruta, """
            CREATE TABLE escrituras (n INTEGER);
            CREATE TRIGGER contar AFTER UPDATE ON sesiones BEGIN
                INSERT INTO escrituras VALUES (1);
            END;
        """)
        for _ in range(5):
            pedir(servidor, "/quien", id)
        pedir(servidor, "/poner/x", id)
        self.assertEqual(sql(self.ruta, "SELECT count(*) FROM escrituras"), [(1,)])

    def test_leer_renueva_la_inactividad_pasada_la_mitad(self):
        a = Tabla(self.ruta, "sesiones", inactividad=100)
        id = a.crear().id
        ahora = sesion.time.time()
        for desfase in (60, 120, 180):
            with mock.patch.object(sesion.time, "time", return_value=ahora + desfase):
                vista = a.recuperar(id)
                self.assertIsNotNone(vista, desfase)
                a.guardar(vista)
