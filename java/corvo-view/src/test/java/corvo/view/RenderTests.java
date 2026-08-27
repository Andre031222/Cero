package corvo.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RenderTests {

    private RenderTests() {
    }

    record Usuario(String nombre, int edad, boolean activo) {
    }

    record Pagina(String titulo, Usuario autor, List<String> etiquetas) {
    }

    static void run() {
        Check.group("interpolación");
        interpolacion();

        Check.group("escapado");
        escapado();

        Check.group("condicionales");
        condicionales();

        Check.group("bucles");
        bucles();
    }

    private static String render(String plantilla, Object modelo) {
        return Template.compile("prueba", plantilla).render(modelo, Templates.from(java.nio.file.Path.of(".")));
    }

    private static void interpolacion() {
        Check.equal("texto sin etiquetas", render("hola", Map.of()), "hola");
        Check.equal("clave de mapa", render("{{ nombre }}", Map.of("nombre", "Ana")), "Ana");
        Check.equal("sin espacios", render("{{nombre}}", Map.of("nombre", "Ana")), "Ana");
        Check.equal("mezclada con texto",
                render("hola {{ nombre }}, adiós", Map.of("nombre", "Ana")), "hola Ana, adiós");
        Check.equal("clave ausente se omite", render("[{{ falta }}]", Map.of()), "[]");
        Check.equal("número", render("{{ n }}", Map.of("n", 42)), "42");
        Check.equal("booleano", render("{{ b }}", Map.of("b", true)), "true");

        Usuario ana = new Usuario("Ana", 30, true);
        Check.equal("componente de record", render("{{ nombre }}", ana), "Ana");
        Check.equal("ruta anidada",
                render("{{ autor.nombre }}", new Pagina("t", ana, List.of())), "Ana");
        Check.equal("ruta anidada de dos niveles",
                render("{{ a.b.c }}", Map.of("a", Map.of("b", Map.of("c", "hondo")))), "hondo");
        Check.equal("ruta rota devuelve vacío",
                render("[{{ a.b.c }}]", Map.of("a", Map.of())), "[]");

        Check.equal("índice de lista",
                render("{{ items.1 }}", Map.of("items", List.of("a", "b", "c"))), "b");
        Check.equal("tamaño de lista",
                render("{{ items.size }}", Map.of("items", List.of("a", "b"))), "2");
        Check.equal("índice fuera de rango",
                render("[{{ items.9 }}]", Map.of("items", List.of("a"))), "[]");

        Check.equal("comentario se elimina", render("a{# nota #}b", Map.of()), "ab");
        Check.equal("llave suelta se conserva", render("a { b", Map.of()), "a { b");
    }

    private static void escapado() {
        Map<String, Object> modelo = Map.of("html", "<b>\"x\" & 'y'</b>");
        Check.equal("escapa por defecto", render("{{ html }}", modelo),
                "&lt;b&gt;&quot;x&quot; &amp; &#39;y&#39;&lt;/b&gt;");
        Check.equal("{{& }} no escapa", render("{{& html }}", modelo), "<b>\"x\" & 'y'</b>");
        Check.equal("texto sin caracteres especiales pasa igual",
                render("{{ t }}", Map.of("t", "limpio")), "limpio");
        Check.equal("escapa el ampersand primero",
                render("{{ t }}", Map.of("t", "&lt;")), "&amp;lt;");
    }

    private static void condicionales() {
        Check.equal("if verdadero",
                render("{% if activo %}sí{% end %}", Map.of("activo", true)), "sí");
        Check.equal("if falso",
                render("{% if activo %}sí{% end %}", Map.of("activo", false)), "");
        Check.equal("else",
                render("{% if activo %}sí{% else %}no{% end %}", Map.of("activo", false)), "no");
        Check.equal("elseif",
                render("{% if a %}A{% elseif b %}B{% else %}C{% end %}", Map.of("a", false, "b", true)), "B");
        Check.equal("elseif cae al else",
                render("{% if a %}A{% elseif b %}B{% else %}C{% end %}", Map.of("a", false, "b", false)), "C");

        Check.equal("cadena vacía es falsa",
                render("{% if t %}sí{% else %}no{% end %}", Map.of("t", "")), "no");
        Check.equal("cadena con texto es verdadera",
                render("{% if t %}sí{% else %}no{% end %}", Map.of("t", "x")), "sí");
        Check.equal("cero es falso",
                render("{% if n %}sí{% else %}no{% end %}", Map.of("n", 0)), "no");
        Check.equal("lista vacía es falsa",
                render("{% if l %}sí{% else %}no{% end %}", Map.of("l", List.of())), "no");
        Check.equal("ausente es falso", render("{% if x %}sí{% else %}no{% end %}", Map.of()), "no");

        Check.equal("negación", render("{% if !a %}no-a{% end %}", Map.of("a", false)), "no-a");
        Check.equal("igualdad con cadena",
                render("{% if rol == \"admin\" %}ok{% end %}", Map.of("rol", "admin")), "ok");
        Check.equal("desigualdad",
                render("{% if rol != \"admin\" %}ok{% end %}", Map.of("rol", "user")), "ok");
        Check.equal("mayor que",
                render("{% if edad > 17 %}mayor{% end %}", Map.of("edad", 18)), "mayor");
        Check.equal("menor o igual",
                render("{% if edad <= 18 %}ok{% end %}", Map.of("edad", 18)), "ok");
        Check.equal("and",
                render("{% if a and b %}ok{% end %}", Map.of("a", true, "b", true)), "ok");
        Check.equal("or",
                render("{% if a or b %}ok{% end %}", Map.of("a", false, "b", true)), "ok");
        Check.equal("paréntesis",
                render("{% if (a or b) and c %}ok{% end %}",
                        Map.of("a", false, "b", true, "c", true)), "ok");
        Check.equal("literal true", render("{% if true %}ok{% end %}", Map.of()), "ok");
        Check.equal("comparación con null",
                render("{% if x == null %}vacío{% end %}", new LinkedHashMap<String, Object>()), "vacío");

        Check.equal("anidado",
                render("{% if a %}{% if b %}ab{% end %}{% end %}", Map.of("a", true, "b", true)), "ab");
    }

    private static void bucles() {
        Map<String, Object> modelo = Map.of("items", List.of("a", "b", "c"));

        Check.equal("recorre la lista",
                render("{% for i in items %}{{ i }}{% end %}", modelo), "abc");
        Check.equal("con separador",
                render("{% for i in items %}{{ i }}{% if !loop.last %},{% end %}{% end %}", modelo), "a,b,c");
        Check.equal("índice base cero",
                render("{% for i in items %}{{ loop.index }}{% end %}", modelo), "012");
        Check.equal("número base uno",
                render("{% for i in items %}{{ loop.number }}{% end %}", modelo), "123");
        Check.equal("primero y último",
                render("{% for i in items %}{% if loop.first %}<{% end %}{{ i }}"
                        + "{% if loop.last %}>{% end %}{% end %}", modelo), "<abc>");
        Check.equal("tamaño",
                render("{% for i in items %}{{ loop.size }}{% end %}", modelo), "333");

        Check.equal("lista vacía no itera",
                render("{% for i in items %}{{ i }}{% end %}", Map.of("items", List.of())), "");
        Check.equal("else en lista vacía",
                render("{% for i in items %}{{ i }}{% else %}nada{% end %}",
                        Map.of("items", List.of())), "nada");
        Check.equal("else no corre con elementos",
                render("{% for i in items %}{{ i }}{% else %}nada{% end %}", modelo), "abc");
        Check.equal("ausente no itera",
                render("[{% for i in nada %}{{ i }}{% end %}]", Map.of()), "[]");

        Check.equal("recorre un array",
                render("{% for i in items %}{{ i }}{% end %}",
                        Map.of("items", new Object[]{"x", "y"})), "xy");

        Check.equal("bucle anidado",
                render("{% for f in filas %}{% for c in f %}{{ c }}{% end %}|{% end %}",
                        Map.of("filas", List.of(List.of(1, 2), List.of(3)))), "12|3|");

        Check.equal("objetos dentro del bucle",
                render("{% for u in usuarios %}{{ u.nombre }}({{ u.edad }}){% end %}",
                        Map.of("usuarios", List.of(new Usuario("Ana", 30, true),
                                new Usuario("Luis", 25, false)))),
                "Ana(30)Luis(25)");

        Check.equal("la variable del bucle no escapa",
                render("{% for i in items %}{% end %}[{{ i }}]", modelo), "[]");
    }
}
