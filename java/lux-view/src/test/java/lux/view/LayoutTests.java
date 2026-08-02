package lux.view;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class LayoutTests {

    private LayoutTests() {
    }

    static void run() throws Exception {
        Path raiz = Files.createTempDirectory("lux-view");

        escribir(raiz, "base.html",
                "<html><head><title>{% block titulo %}sin título{% end %}</title></head>"
                        + "<body>{% block contenido %}vacío{% end %}<footer>pie</footer></body></html>");
        escribir(raiz, "hija.html",
                "{% extends \"base.html\" %}"
                        + "{% block titulo %}{{ titulo }}{% end %}"
                        + "{% block contenido %}<p>{{ cuerpo }}</p>{% end %}");
        escribir(raiz, "parcial.html", "<li>{{ item }}</li>");
        escribir(raiz, "lista.html",
                "<ul>{% for item in items %}{% include \"parcial.html\" %}{% end %}</ul>");
        escribir(raiz, "nieta.html",
                "{% extends \"hija.html\" %}{% block titulo %}nieta{% end %}");
        escribir(raiz, "sin-bloques.html", "{% extends \"base.html\" %}");
        escribir(raiz, "cicloA.html", "{% extends \"cicloB.html\" %}");
        escribir(raiz, "cicloB.html", "{% extends \"cicloA.html\" %}");

        Templates plantillas = Templates.from(raiz);

        Check.group("herencia e inclusión");

        Check.equal("la hija rellena los bloques del padre",
                plantillas.render("hija.html", Map.of("titulo", "Inicio", "cuerpo", "hola")),
                "<html><head><title>Inicio</title></head><body><p>hola</p><footer>pie</footer></body></html>");

        Check.equal("los bloques sin sobrescribir usan su valor por defecto",
                plantillas.render("sin-bloques.html", Map.of()),
                "<html><head><title>sin título</title></head><body>vacío<footer>pie</footer></body></html>");

        Check.that("la herencia encadena dos niveles",
                plantillas.render("nieta.html", Map.of("cuerpo", "x")).contains("<title>nieta</title>"));
        Check.that("y conserva los bloques del nivel intermedio",
                plantillas.render("nieta.html", Map.of("cuerpo", "x")).contains("<p>x</p>"));

        Check.equal("include dentro de un bucle",
                plantillas.render("lista.html", Map.of("items", List.of("a", "b"))),
                "<ul><li>a</li><li>b</li></ul>");

        Check.raises("la herencia circular se detecta", TemplateException.class,
                () -> plantillas.render("cicloA.html", Map.of()));

        Check.group("carga y caché");

        Check.equal("cachea la plantilla compilada", plantillas.cached() > 0, true);
        Check.raises("plantilla inexistente falla", TemplateException.class,
                () -> plantillas.render("no-esta.html", Map.of()));
        Check.raises("no se puede salir del directorio raíz", TemplateException.class,
                () -> plantillas.render("../fuera.html", Map.of()));

        Templates conSufijo = Templates.from(raiz).suffix(".html");
        Check.that("el sufijo se añade solo",
                conSufijo.render("parcial", Map.of("item", "z")).equals("<li>z</li>"));
        Check.that("y no se duplica si ya viene",
                conSufijo.render("parcial.html", Map.of("item", "z")).equals("<li>z</li>"));

        Path mutable = raiz.resolve("mutable.html");
        Files.writeString(mutable, "uno");
        Templates recarga = Templates.from(raiz).reload(true);
        Check.equal("primera lectura", recarga.render("mutable.html", Map.of()), "uno");
        Thread.sleep(1_100);
        Files.writeString(mutable, "dos");
        Check.equal("con reload la plantilla se recompila",
                recarga.render("mutable.html", Map.of()), "dos");

        Templates fija = Templates.from(raiz);
        fija.render("mutable.html", Map.of());
        Files.writeString(mutable, "tres");
        Check.equal("sin reload se sirve la versión cacheada",
                fija.render("mutable.html", Map.of()), "dos");
        fija.clearCache();
        Check.equal("clearCache fuerza la relectura",
                fija.render("mutable.html", Map.of()), "tres");

        Check.group("errores de plantilla");

        Check.raises("etiqueta desconocida", TemplateException.class,
                () -> Template.compile("t", "{% raro %}"));
        Check.raises("if sin end", TemplateException.class,
                () -> Template.compile("t", "{% if a %}x"));
        Check.raises("for sin end", TemplateException.class,
                () -> Template.compile("t", "{% for i in l %}x"));
        Check.raises("end sobrante", TemplateException.class,
                () -> Template.compile("t", "hola {% end %}"));
        Check.raises("interpolación sin cerrar", TemplateException.class,
                () -> Template.compile("t", "{{ a "));
        Check.raises("interpolación vacía", TemplateException.class,
                () -> Template.compile("t", "{{ }}"));
        Check.raises("for mal formado", TemplateException.class,
                () -> Template.compile("t", "{% for %}{% end %}"));
        Check.raises("bloque sin nombre", TemplateException.class,
                () -> Template.compile("t", "{% block %}{% end %}"));
        Check.raises("expresión inválida", TemplateException.class,
                () -> Template.compile("t", "{% if a == %}{% end %}"));

        try {
            Template.compile("pagina.html", "linea\n{% raro %}");
        } catch (TemplateException error) {
            Check.that("el error indica plantilla y línea",
                    error.getMessage().startsWith("pagina.html:2"));
        }
    }

    private static void escribir(Path raiz, String nombre, String contenido) throws Exception {
        Files.writeString(raiz.resolve(nombre), contenido);
    }
}
