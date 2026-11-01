import time
import unittest

from html.parser import HTMLParser

from cero.saneado import PROFUNDIDAD, sanear_html, sanear_nombre, sanear_texto

PERMITIDAS = {"p", "b", "i", "em", "strong", "a", "ul", "ol", "li", "br", "code", "pre",
              "blockquote", "div", "span"}


def marcado(html):
    vistas = []

    class Lector(HTMLParser):
        def handle_starttag(self, etiqueta, atributos):
            vistas.append((etiqueta, atributos))
    lector = Lector()
    lector.feed(html)
    lector.close()
    return vistas

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
    "<<b></b>script>alert(1)<<b></b>/script>",
    "<<b>img src=x onerror=alert(1)>",
    "<<script>script>alert(1)<</script>/script>",
    "&lt;script&gt;alert(1)&lt;/script&gt;",
]

ABUSOS = [
    ("<form action=\"https://evil.pe\"><input type=password name=clave></form>",
     ("<form", "<input", "action=", "name=")),
    ("<button form=login formaction=\"https://evil.pe\">ok</button>",
     ("<button", "formaction", "form=")),
    ("<img name=getElementById src=x>", ("<img", "name=")),
    ("<p id=config name=x>hola</p>", ("id=", "name=")),
    ("<div style=\"position:fixed;top:0;left:0;width:100%\">x</div>", ("style=",)),
    ("<p style=\"width:expression(alert(1))\">x</p>", ("style=", "expression")),
    ("<a href=\"data:text/html;base64,PHNjcmlwdD4=\">x</a>", ("data:", "href=")),
    ("<a href=\"data:image/svg+xml,<svg onload=alert(1)>\">x</a>", ("data:", "href=", "<svg")),
    ("<p srcdoc=\"<script>alert(1)</script>\">x</p>", ("srcdoc", "script")),
    ("<svg><a href=\"https://x.pe\"><text>x</text></a></svg>", ("<svg", "<a", "<text", ">x<")),
    ("<math><mi>secreto</mi></math>", ("<math", "secreto")),
    ("<template><p>dentro</p></template>", ("<template", "dentro")),
    ("<a href=\"vbscript:msgbox(1)\">x</a>", ("vbscript", "href=")),
    ("<a href=\"ftp://x.pe\">x</a>", ("ftp", "href=")),
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
                limpio = sanear_html(ataque)
                for etiqueta, atributos in marcado(limpio):
                    self.assertIn(etiqueta, PERMITIDAS)
                    for nombre, valor in atributos:
                        self.assertIn((etiqueta, nombre), {("a", "href"), ("a", "title")})
                        if nombre == "href":
                            self.assertRegex(valor, r"^(https?:|mailto:|[^:]*$)")
                self.assertNotRegex(limpio, r"<(?!/?(" + "|".join(PERMITIDAS) + r")[ >/])")

    def test_seg_023_solo_pasa_lo_que_esta_en_la_lista(self):
        for abuso, rastros in ABUSOS:
            with self.subTest(abuso=abuso):
                limpio = sanear_html(abuso).lower()
                for rastro in rastros:
                    self.assertNotIn(rastro, limpio)

    def test_seg_024_los_enlaces_seguros_se_quedan(self):
        for href in ("https://x.pe/a", "http://x.pe", "mailto:ana@x.pe", "/relativo", "otra.html",
                     "#ancla", "?q=1"):
            with self.subTest(href=href):
                html = f'<a href="{href}">x</a>'
                self.assertEqual(sanear_html(html), html)

    def test_seg_024_conserva_el_marcado_inocuo(self):
        for inocuo in ("<p><b>hola</b></p>",
                       "<ul><li>uno</li><li><i>dos</i></li></ul>",
                       "<a href=\"https://x.pe/?a=1&amp;b=2\" title=\"t\">enlace</a>",
                       "uno<br>dos",
                       "<blockquote><p><em>a</em> <strong>b</strong></p></blockquote>",
                       "<pre><code>x = 1</code></pre>",
                       "<ol><li>uno</li></ol><div><span>x</span></div>",
                       "a &amp; b &lt; c",
                       "texto sin marcado"):
            with self.subTest(inocuo=inocuo):
                self.assertEqual(sanear_html(inocuo), inocuo)

    def test_seg_024_de_la_etiqueta_peligrosa_solo_quita_lo_peligroso(self):
        self.assertEqual(sanear_html("<p onclick=\"x()\" class=\"c\">hola</p>"), "<p>hola</p>")
        self.assertEqual(sanear_html("<section><h1>t</h1><p>x</p></section>"), "t<p>x</p>")
        self.assertEqual(sanear_html("<a href=\"javascript:x()\" title=\"t\">y</a>"),
                         "<a title=\"t\">y</a>")

    def test_lo_que_queda_abierto_se_cierra_al_final(self):
        self.assertEqual(sanear_html("<b>sin cerrar"), "<b>sin cerrar</b>")
        self.assertEqual(sanear_html("<div><p><a href=\"/x\">y"),
                         "<div><p><a href=\"/x\">y</a></p></div>")
        self.assertEqual(sanear_html("<b/>suelto"), "<b></b>suelto")

    def test_un_cierre_sin_apertura_se_tira(self):
        self.assertEqual(sanear_html("</div>hola</p>"), "hola")
        self.assertEqual(sanear_html("<p>a</p></p></div>"), "<p>a</p>")
        self.assertEqual(sanear_html("<b>x</i></b>"), "<b>x</b>")

    def test_un_cruce_se_cierra_en_orden(self):
        self.assertEqual(sanear_html("<b><i>x</b>y</i>"), "<b><i>x</i></b>y")

    def test_la_salida_siempre_esta_equilibrada(self):
        for entrada in ATAQUES + [a for a, _ in ABUSOS] + ["<p><b>a<i>b</p>c</b>", "</a></a><a>"]:
            with self.subTest(entrada=entrada):
                pila = []

                class Lector(HTMLParser):
                    def handle_starttag(self, etiqueta, atributos):
                        if etiqueta != "br":
                            pila.append(etiqueta)

                    def handle_endtag(self, etiqueta):
                        cima = pila.pop() if pila else None
                        assert cima == etiqueta, (cima, etiqueta)
                lector = Lector()
                lector.feed(sanear_html(entrada))
                lector.close()
                self.assertEqual(pila, [])

    def test_una_entrada_hostil_de_un_mega_no_se_vuelve_cuadratica(self):
        for entrada in ("<b>" * 170_000 + "</i>" * 85_000,
                        "<div>" * 100_000 + "</div>" * 100_000,
                        "<p><b>" * 70_000 + "</p>" * 70_000):
            with self.subTest(entrada=entrada[:12]):
                inicio = time.process_time()
                salida = sanear_html(entrada)
                self.assertLess(time.process_time() - inicio, 1)
                self.assertEqual(salida.count("<"), salida.count("</") * 2)

    def test_la_profundidad_tiene_tope(self):
        salida = sanear_html("<div>" * 1000 + "x" + "</div>" * 1000)
        self.assertLessEqual(salida.count("<div>"), PROFUNDIDAD)
        self.assertEqual(salida.count("<div>"), salida.count("</div>"))
        self.assertIn("x", salida)

    def test_el_texto_que_parece_marcado_sale_escapado(self):
        self.assertEqual(sanear_html("<a title='\"><script>'>x</a>"),
                         "<a title=\"&quot;&gt;&lt;script&gt;\">x</a>")


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

    def test_seg_025_lo_que_queda_no_se_vuelve_a_juntar_en_etiquetas(self):
        self.assertEqual(sanear_texto("<<b></b>script>alert(1)<<b></b>/script>"),
                         "&lt;script&gt;alert(1)&lt;/script&gt;")
        self.assertEqual(sanear_texto("&lt;script&gt;x&lt;/script&gt;"),
                         "&lt;script&gt;x&lt;/script&gt;")

    def test_el_texto_sale_escapado(self):
        self.assertEqual(sanear_texto("a &amp; b > c"), "a &amp; b &gt; c")


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
