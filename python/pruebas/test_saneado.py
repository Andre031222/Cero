import unittest

from cero.saneado import sanear_html, sanear_nombre, sanear_texto

ATAQUES = [
    "<script>alert(1)</script>",
    "<SCRIPT SRC=//x.pe/a.js></SCRIPT>",
    "<script>alert(1)",
    "<scr<script>ipt>alert(1)</script>",
    "<img src=x onerror=alert(1)>",
    "<img/onerror=alert(1) src=x>",
    "<IMG SRC=x OnErRoR=alert(1)>",
    "<svg onload=alert(1)>",
    "<body onload=alert(1)>",
    "<a href=\"javascript:alert(1)\">x</a>",
    "<a href=\" JaVa\tScRiPt:alert(1)\">x</a>",
    "<a href=\"&#106;avascript:alert(1)\">x</a>",
    "<a href=\"java&#x0A;script:alert(1)\">x</a>",
    "<iframe srcdoc=\"<script>alert(1)</script>\"></iframe>",
    "<iframe><iframe></iframe><script>alert(1)</script></iframe>",
    "<style>@import 'javascript:alert(1)';</style>",
    "<object data=\"javascript:alert(1)\"></object>",
    "<embed src=\"javascript:alert(1)\">",
    "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>",
    "<math><a xlink:href=\"javascript:alert(1)\">x</a></math>",
    "<div style=\"background:url(javascript:alert(1))\">x</div>",
    "<!--<script>alert(1)</script>-->",
    "<b onclick=alert(1)",
    "<svg><set attributeName=onmouseover to=alert(1) /><animate attributeName=href "
    "values=javascript:alert(1)>",
    "<svg><style><img src=x onerror=alert(1)></style></svg>",
    "<form><math><mtext></form><form><mglyph><style></math><img src onerror=alert(1)>",
    "<a href=\"javascript&colon;alert(1)\">x</a>",
]


class SaneadoHtml(unittest.TestCase):

    def test_seg_023_elimina_script_style_e_iframe(self):
        self.assertEqual(sanear_html("hola <script>alert(1)</script> mundo"), "hola  mundo")
        self.assertEqual(sanear_html("a<style>body{}</style>b"), "ab")
        self.assertEqual(sanear_html("a<iframe src=x></iframe>b"), "ab")

    def test_seg_023_elimina_manejadores_y_javascript(self):
        self.assertEqual(sanear_html("<div onclick=\"malo()\">x</div>"), "<div>x</div>")
        self.assertEqual(sanear_html("<a href=\"javascript:malo()\">x</a>"), "<a>x</a>")

    def test_seg_023_ninguna_variante_deja_algo_ejecutable(self):
        for ataque in ATAQUES:
            with self.subTest(ataque=ataque):
                limpio = sanear_html(ataque).lower()
                for rastro in ("<script", "<iframe", "<style", "<object", "<embed", "<noscript",
                               "onerror", "onload", "onclick", "javascript:", "xlink"):
                    self.assertNotIn(rastro, limpio)

    def test_seg_024_conserva_el_marcado_inocuo(self):
        for inocuo in ("<p><b>hola</b></p>",
                       "<ul><li>uno</li><li><i>dos</i></li></ul>",
                       "<a href=\"https://x.pe/?a=1&amp;b=2\" title=\"t\">enlace</a>",
                       "uno<br>dos",
                       "a &amp; b &lt; c",
                       "texto sin marcado"):
            with self.subTest(inocuo=inocuo):
                self.assertEqual(sanear_html(inocuo), inocuo)

    def test_seg_024_de_la_etiqueta_peligrosa_solo_quita_lo_peligroso(self):
        self.assertEqual(sanear_html("<img src=\"a.png\" onerror=\"x()\" alt=\"a\">"),
                         "<img src=\"a.png\" alt=\"a\">")
        self.assertEqual(sanear_html("<a href=\"javascript:x()\" title=\"t\">y</a>"),
                         "<a title=\"t\">y</a>")

    def test_el_texto_que_parece_marcado_sale_escapado(self):
        self.assertEqual(sanear_html("<p title='\"><script>'>x</p>"),
                         "<p title=\"&quot;&gt;&lt;script&gt;\">x</p>")


class SaneadoTexto(unittest.TestCase):

    def test_seg_025_quita_toda_etiqueta(self):
        self.assertEqual(sanear_texto("<p>hola <b>mundo</b></p>"), "hola mundo")

    def test_seg_025_no_deja_rastro_de_script(self):
        self.assertEqual(sanear_texto("<script>alert(1)</script>texto"), "texto")
        self.assertEqual(sanear_texto("a<style>b{}</style><iframe>c</iframe>d"), "ad")
        self.assertEqual(sanear_texto("antes<script>alert(1)"), "antes")

    def test_seg_025_ninguna_variante_deja_etiquetas(self):
        for ataque in ATAQUES:
            with self.subTest(ataque=ataque):
                self.assertNotIn("<", sanear_texto(ataque))

    def test_el_texto_sale_como_texto(self):
        self.assertEqual(sanear_texto("a &amp; b"), "a & b")


class SaneadoNombre(unittest.TestCase):

    def test_seg_026_quita_rutas_y_separadores_de_los_dos_sistemas(self):
        self.assertEqual(sanear_nombre("../../etc/passwd"), "passwd")
        self.assertEqual(sanear_nombre("C:\\temp\\a.txt"), "a.txt")
        self.assertEqual(sanear_nombre("..\\..\\windows/system32\\a.dll"), "a.dll")
        self.assertEqual(sanear_nombre("/etc/"), "archivo")

    def test_seg_026_nunca_devuelve_un_nombre_vacio(self):
        for nombre in ("", "...", "..", ".", "/", "\\", "   ", "\x00", "../"):
            with self.subTest(nombre=nombre):
                self.assertEqual(sanear_nombre(nombre), "archivo")

    def test_seg_026_limpia_lo_raro_y_deja_lo_normal(self):
        self.assertEqual(sanear_nombre("in forme;rm -rf.pdf"), "in_forme_rm_-rf.pdf")
        self.assertEqual(sanear_nombre("informe_2026.pdf"), "informe_2026.pdf")
        self.assertEqual(sanear_nombre("a.txt:oculto"), "a.txt_oculto")
        self.assertEqual(sanear_nombre(".bashrc"), "bashrc")

    def test_seg_026_nombres_reservados_de_windows(self):
        self.assertEqual(sanear_nombre("CON"), "_CON")
        self.assertEqual(sanear_nombre("nul.txt"), "_nul.txt")
        self.assertEqual(sanear_nombre("console.txt"), "console.txt")

    def test_un_nombre_larguisimo_se_corta(self):
        self.assertEqual(len(sanear_nombre("a" * 1000 + ".pdf")), 255)


if __name__ == "__main__":
    unittest.main()
