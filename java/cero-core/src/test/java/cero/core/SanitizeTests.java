package cero.core;

import java.util.List;
import java.util.Locale;

final class SanitizeTests {

    private SanitizeTests() {
    }

    /** Vectores que tienen que salir sin nada ejecutable. Uno por línea, con su nombre. */
    private static final List<String[]> VECTORES = List.of(
            new String[] {"script simple", "<script>alert(1)</script>"},
            new String[] {"script con atributos", "<script type=\"text/javascript\">alert(1)</script>"},
            new String[] {"script en mayúsculas", "<SCRIPT>alert(1)</SCRIPT>"},
            new String[] {"script mezclado", "<ScRiPt>alert(1)</sCrIpT>"},
            new String[] {"script sin cerrar", "<script>alert(1)"},
            new String[] {"script anidado en su propio nombre", "<scr<script>ipt>alert(1)</script>"},
            new String[] {"script con espacio antes del cierre", "<script >alert(1)</script >"},
            new String[] {"svg con barra y onload", "<svg/onload=alert(1)>"},
            new String[] {"svg con onload normal", "<svg onload=alert(1)>"},
            new String[] {"body con barra y onpageshow", "<body/onpageshow=alert(1)>"},
            new String[] {"img con onerror", "<img src=x onerror=alert(1)>"},
            new String[] {"img con onerror entre comillas", "<img src=x onerror=\"alert(1)\">"},
            new String[] {"img con onerror en varias líneas", "<img\nsrc=x\nonerror=alert(1)>"},
            new String[] {"img con onerror tras tabulador", "<img\tsrc=x\tonerror=alert(1)>"},
            new String[] {"onerror en mayúsculas", "<img src=x ONERROR=alert(1)>"},
            new String[] {"enlace javascript", "<a href=\"javascript:alert(1)\">x</a>"},
            new String[] {"enlace javascript con entidad", "<a href=\"jav&#97;script:alert(1)\">x</a>"},
            new String[] {"enlace javascript con entidad hexadecimal",
                    "<a href=\"&#x6a;avascript:alert(1)\">x</a>"},
            new String[] {"enlace javascript con tabulador", "<a href=\"jav&#9;ascript:alert(1)\">x</a>"},
            new String[] {"enlace javascript con salto", "<a href=\"java\nscript:alert(1)\">x</a>"},
            new String[] {"enlace javascript con espacios delante", "<a href=\"   javascript:alert(1)\">x</a>"},
            new String[] {"enlace javascript en mayúsculas", "<a href=\"JaVaScRiPt:alert(1)\">x</a>"},
            new String[] {"enlace vbscript", "<a href=\"vbscript:msgbox(1)\">x</a>"},
            new String[] {"enlace data con html", "<a href=\"data:text/html;base64,PHN2Zz4=\">x</a>"},
            new String[] {"imagen con src javascript", "<img src=\"javascript:alert(1)\">"},
            new String[] {"iframe", "<iframe src=\"https://mala.pe\"></iframe>"},
            new String[] {"iframe con javascript", "<iframe src=\"javascript:alert(1)\"></iframe>"},
            new String[] {"object", "<object data=\"x.swf\"></object>"},
            new String[] {"embed", "<embed src=\"x.swf\">"},
            new String[] {"form con acción", "<form action=\"https://mala.pe\"><input name=a></form>"},
            new String[] {"style con expresión", "<style>body{background:url(javascript:alert(1))}</style>"},
            new String[] {"atributo style", "<div style=\"background:url(javascript:alert(1))\">x</div>"},
            new String[] {"meta refresh", "<meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\">"},
            new String[] {"link a hoja de estilo", "<link rel=stylesheet href=\"https://mala.pe/x.css\">"},
            new String[] {"base", "<base href=\"https://mala.pe/\">"},
            new String[] {"comentario condicional", "<!--[if IE]><script>alert(1)</script><![endif]-->"},
            new String[] {"comentario sin cerrar", "<!-- <script>alert(1)</script>"},
            new String[] {"marcador de sección", "<![CDATA[<script>alert(1)</script>]]>"},
            new String[] {"instrucción de proceso", "<?xml-stylesheet href=\"javascript:alert(1)\"?>"},
            new String[] {"detalles con ontoggle", "<details open ontoggle=alert(1)>"},
            new String[] {"entrada con onfocus y autofoco", "<input autofocus onfocus=alert(1)>"},
            new String[] {"marquesina con onstart", "<marquee onstart=alert(1)>x</marquee>"},
            new String[] {"select con onchange", "<select onchange=alert(1)><option>a</option></select>"},
            new String[] {"textarea con contenido", "<textarea><script>alert(1)</script></textarea>"},
            new String[] {"plantilla", "<template><script>alert(1)</script></template>"},
            new String[] {"noscript", "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\">"},
            new String[] {"math con acción", "<math><maction actiontype=\"statusline#\">x</maction></math>"},
            new String[] {"comilla suelta en atributo", "<a href=\"x\" title=\"a\"b onmouseover=alert(1)\">x</a>"},
            new String[] {"nulo dentro del nombre", "<scr\0ipt>alert(1)</scr\0ipt>"},
            new String[] {"etiqueta con espacio inicial", "< script>alert(1)</script>"},
            new String[] {"doble codificación", "%3Cscript%3Ealert(1)%3C/script%3E"},
            new String[] {"formulario dentro de un párrafo",
                    "<p><form><button formaction=javascript:alert(1)>x</button></form></p>"});

    static void run() {
        Check.group("sanear · nada ejecutable sale");

        for (String[] caso : VECTORES) {
            Check.that(caso[0], inofensivo(Sanitize.html(caso[1])));
        }

        Check.group("sanear · en texto plano tampoco");

        for (String[] caso : VECTORES) {
            String salida = Sanitize.text(caso[1]);
            Check.that(caso[0], inofensivo(salida) && sinEtiquetas(salida));
        }

        estructura();
        direcciones();
        equilibrio();
        limites();
        bordes();
    }

    /** Lo que sí tiene que conservarse: si borrara todo, también «pasaría» las de arriba. */
    private static void estructura() {
        Check.group("sanear · lo permitido se conserva");

        Check.equal("un párrafo", Sanitize.html("<p>hola</p>"), "<p>hola</p>");
        Check.equal("negrita y cursiva", Sanitize.html("<b>a</b><i>b</i>"), "<b>a</b><i>b</i>");
        Check.equal("una lista", Sanitize.html("<ul><li>a</li><li>b</li></ul>"),
                "<ul><li>a</li><li>b</li></ul>");
        Check.equal("un encabezado", Sanitize.html("<h2>t</h2>"), "<h2>t</h2>");
        Check.equal("una cita", Sanitize.html("<blockquote>c</blockquote>"), "<blockquote>c</blockquote>");
        Check.equal("código preformateado", Sanitize.html("<pre><code>x</code></pre>"),
                "<pre><code>x</code></pre>");
        Check.equal("un salto de línea", Sanitize.html("a<br>b"), "a<br>b");
        Check.equal("un salto autocerrado", Sanitize.html("a<br/>b"), "a<br>b");
        Check.equal("una tabla", Sanitize.html("<table><tr><td>1</td></tr></table>"),
                "<table><tr><td>1</td></tr></table>");
        Check.equal("colspan se mantiene", Sanitize.html("<td colspan=\"2\">x</td>"),
                "<td colspan=\"2\">x</td>");
        Check.equal("el texto se escapa", Sanitize.html("3 < 5 & 6 > 4"), "3 &lt; 5 &amp; 6 &gt; 4");
        Check.equal("las comillas también", Sanitize.html("di \"hola\""), "di &quot;hola&quot;");
        Check.equal("una etiqueta desconocida se cae pero su texto no",
                Sanitize.html("<desconocida>texto</desconocida>"), "texto");
        Check.equal("el nombre de la etiqueta se normaliza", Sanitize.html("<P>x</P>"), "<p>x</p>");
        Check.that("un atributo no listado no se emite",
                !Sanitize.html("<p class=\"x\" id=\"y\">t</p>").contains("class"));
        Check.equal("el alt de una imagen sí", Sanitize.html("<img src=\"/a.png\" alt=\"foto\">"),
                "<img src=\"/a.png\" alt=\"foto\">");
        Check.equal("un atributo sin comillas se lee hasta el espacio, como el navegador",
                Sanitize.html("<img/src=x/onerror=alert(1)>"), "<img src=\"x/onerror=alert(1)\">");
    }

    private static void direcciones() {
        Check.group("sanear · direcciones");

        Check.that("http pasa", Sanitize.html("<a href=\"http://ginit.dev\">x</a>").contains("href"));
        Check.that("https pasa", Sanitize.html("<a href=\"https://ginit.dev\">x</a>").contains("href"));
        Check.that("mailto pasa", Sanitize.html("<a href=\"mailto:a@b.pe\">x</a>").contains("href"));
        Check.that("tel pasa", Sanitize.html("<a href=\"tel:+51999\">x</a>").contains("href"));
        Check.that("una ruta relativa pasa", Sanitize.html("<a href=\"/guia\">x</a>").contains("href"));
        Check.that("un ancla pasa", Sanitize.html("<a href=\"#seccion\">x</a>").contains("href"));
        Check.that("una relativa con dos puntos después de la barra pasa",
                Sanitize.html("<a href=\"/a/b:c\">x</a>").contains("href"));
        Check.that("un esquema raro no pasa",
                !Sanitize.html("<a href=\"file:///etc/passwd\">x</a>").contains("href"));
        Check.that("data no pasa", !Sanitize.html("<a href=\"data:text/html,x\">x</a>").contains("href"));
        Check.that("pero el enlace sobrevive sin href",
                Sanitize.html("<a href=\"javascript:alert(1)\">texto</a>").contains("texto"));
        Check.that("un enlace admitido lleva rel",
                Sanitize.html("<a href=\"https://x.pe\">t</a>")
                        .contains("rel=\"nofollow noopener noreferrer\""));
        Check.that("uno sin href no lleva rel", !Sanitize.html("<a>t</a>").contains("rel="));

        // El valor lleva `&quot;` con la intención de cerrar la comilla y colar un manejador.
        // Al reescribirlo, el `&` se escapa, así que sale como texto y no cierra nada.
        String colado = Sanitize.html("<a href=\"/a&quot;onmouseover=alert(1)\">x</a>");
        Check.that("una comilla codificada no cierra el atributo", colado.contains("&amp;quot;"));
        Check.that("y el manejador queda dentro del valor", !colado.contains("\" onmouseover"));
    }

    private static void equilibrio() {
        Check.group("sanear · la salida siempre cierra");

        Check.equal("una etiqueta sin cerrar se cierra", Sanitize.html("<p>hola"), "<p>hola</p>");
        Check.equal("varias, en orden", Sanitize.html("<div><p>a"), "<div><p>a</p></div>");
        Check.equal("un cierre huérfano se descarta", Sanitize.html("hola</p>"), "hola");
        Check.equal("un cierre cruzado no desordena", Sanitize.html("<b><i>x</b></i>"), "<b><i>x</i></b>");
        Check.equal("el vacío no lleva cierre", Sanitize.html("<hr>"), "<hr>");
        Check.that("nunca queda un '<' suelto sin escapar",
                Sanitize.html("<p>a < b</p>").indexOf("< ") < 0);
    }

    private static void limites() {
        Check.group("sanear · límites");

        String salida = Sanitize.html("<div>".repeat(5_000) + "x");
        Check.that("una entrada muy anidada no revienta", salida.contains("x"));
        Check.that("y no emite más cierres que el tope", contar(salida, "</div>") <= 100);

        Check.that("una entrada larga se procesa",
                Sanitize.html("<p>a</p>".repeat(20_000)).length() > 0);
        Check.that("muchas etiquetas sin cerrar tampoco",
                Sanitize.html("<b>".repeat(5_000)).length() > 0);
    }

    private static void bordes() {
        Check.group("sanear · bordes");

        Check.equal("null se devuelve tal cual", Sanitize.html(null), null);
        Check.equal("la cadena vacía también", Sanitize.html(""), "");
        Check.equal("texto sin nada que hacer", Sanitize.html("hola"), "hola");
        Check.equal("un '<' al final", Sanitize.html("hola <"), "hola &lt;");
        Check.equal("un '<' con un número detrás", Sanitize.html("a <3 b"), "a &lt;3 b");
        Check.equal("una etiqueta sin cerrar el corchete", Sanitize.html("<p"), "<p></p>");
        Check.equal("text() recoge los espacios", Sanitize.text("<p>a</p>   <p>b</p>"), "a b");
        Check.equal("text() con null", Sanitize.text(null), null);
        Check.equal("text() no deja etiquetas", Sanitize.text("<b>hola</b>"), "hola");

        Check.group("sanear · nombres de archivo");

        Check.equal("una ruta se queda en el nombre", Sanitize.filename("/etc/passwd"), "passwd");
        Check.equal("una ruta de Windows también", Sanitize.filename("C:\\tmp\\a.txt"), "a.txt");
        Check.equal("el traversal desaparece", Sanitize.filename("../../etc/passwd"), "passwd");
        Check.equal("los ocultos dejan de serlo", Sanitize.filename(".bashrc"), "bashrc");
        Check.equal("los caracteres raros se sustituyen", Sanitize.filename("a b;c.txt"), "a_b_c.txt");
        Check.equal("un nombre que se queda vacío tiene respaldo", Sanitize.filename("..."), "archivo");
        Check.equal("null sigue siendo null", Sanitize.filename(null), null);
    }

    /** Ni etiqueta ejecutable, ni manejador, ni esquema de guion en la salida. */
    private static boolean inofensivo(String salida) {
        String s = salida.toLowerCase(Locale.ROOT);
        if (s.contains("<script") || s.contains("<iframe") || s.contains("<object")
                || s.contains("<embed") || s.contains("<svg") || s.contains("<form")
                || s.contains("<style") || s.contains("<meta") || s.contains("<link")
                || s.contains("<base") || s.contains("<template") || s.contains("<math")) {
            return false;
        }
        if (s.contains("javascript:") || s.contains("vbscript:") || s.contains("data:text/html")) {
            return false;
        }
        return !manejadorEnEtiqueta(s);
    }

    /**
     * Busca un atributo cuyo nombre empiece por {@code on}. Mira solo fuera de los valores
     * entrecomillados: dentro de uno, `onerror=` es texto y el navegador tampoco lo ejecuta.
     */
    private static boolean manejadorEnEtiqueta(String salida) {
        int i = 0;
        while ((i = salida.indexOf('<', i)) >= 0) {
            int fin = salida.indexOf('>', i);
            if (fin < 0) {
                return false;
            }
            boolean entrecomillado = false;
            for (int p = i; p < fin; p++) {
                char c = salida.charAt(p);
                if (c == '"') {
                    entrecomillado = !entrecomillado;
                } else if (!entrecomillado && (c == 'o' || c == 'O')
                        && nombreDeManejador(salida, p, fin)) {
                    return true;
                }
            }
            i = fin + 1;
        }
        return false;
    }

    /** {@code true} si en {@code p} empieza `on…=` y antes hay un separador de atributos. */
    private static boolean nombreDeManejador(String salida, int p, int fin) {
        if (p == 0 || !Character.isWhitespace(salida.charAt(p - 1))) {
            return false;
        }
        if (p + 2 >= fin || Character.toLowerCase(salida.charAt(p + 1)) != 'n') {
            return false;
        }
        int q = p + 2;
        while (q < fin && Character.isLetter(salida.charAt(q))) {
            q++;
        }
        return q > p + 2 && q < fin && salida.charAt(q) == '=';
    }

    /** {@code true} si no queda ninguna etiqueta: ni {@code <a…} ni {@code </a…}. */
    private static boolean sinEtiquetas(String salida) {
        for (int i = 0; i + 1 < salida.length(); i++) {
            if (salida.charAt(i) != '<') {
                continue;
            }
            char siguiente = salida.charAt(i + 1);
            if (Character.isLetter(siguiente) || siguiente == '/') {
                return false;
            }
        }
        return true;
    }

    private static int contar(String texto, String aguja) {
        int total = 0;
        int i = 0;
        while ((i = texto.indexOf(aguja, i)) >= 0) {
            total++;
            i += aguja.length();
        }
        return total;
    }
}
