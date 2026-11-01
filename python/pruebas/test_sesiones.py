import threading
import unittest
from unittest import mock

from cero import sesion
from cero.sesion import Memoria, SesionInvalidada


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
