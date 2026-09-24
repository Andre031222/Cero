package ejemplo;

import cero.data.DataSources;
import cero.test.TestClient;
import cero.test.TestResponse;
import cero.test.TestServer;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TestSuite {

    private static final Pattern CSRF =
            Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private static int passed;
    private static int failed;

    private static TestServer app;

    private TestSuite() {
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("── aplicación de ejemplo, de punta a punta");

        try (TestServer servidor = TestServer.start(App.app("jdbc:h2:mem:pruebas;DB_CLOSE_DELAY=-1"))) {
            app = servidor;
            paginaInicial();
            herenciaDePlantillas();
            cabecerasDeSeguridad();
            cabecerasEnElDocumentoHtml();
            prohibido();
            altaPorFormulario();
            tablaDeTareas();
            csrf();
            validacionDeFormulario();
            api();
            estaticos();
            sesionSobreHttp2();
            cors();
        } finally {
            DataSources.clear();
        }

        System.out.println();
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("  TOTAL  pass=%d  fail=%d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void paginaInicial() {
        TestResponse portada = cliente().get("/");
        check("la portada responde 200", portada.status() == 200);
        check("se sirve como HTML", tipo(portada).startsWith("text/html"));
        check("el layout se aplica", portada.body().startsWith("<!doctype html>"));
        check("el bloque de título se rellena", portada.body().contains("<title>Tareas · 0 pendientes"));
        check("sin tareas muestra el aviso", portada.body().contains("No hay ninguna tarea"));
        check("emite el token CSRF en el formulario", token(portada.body()) != null);
        check("abre sesión", portada.raw().headers().firstValue("set-cookie").isPresent());
        check("/salud responde", cliente().get("/salud").body().equals("ok"));
    }

    /** El layout de base.html se aplica y la vista hija hereda de él rellenando sus bloques. */
    private static void herenciaDePlantillas() {
        String pagina = cliente().get("/").body();
        check("el layout envuelve el documento entero",
                pagina.startsWith("<!doctype html>") && pagina.trim().endsWith("</html>"));
        check("el layout aporta su cabecera común", pagina.contains("<h1>Tareas</h1>"));
        check("y el enlace a la hoja de estilo",
                pagina.contains("href=\"/estaticos/estilo.css\""));
        check("la vista hija sobrescribe el bloque de título",
                pagina.contains("<title>Tareas · 0 pendientes</title>"));
        check("y rellena el bloque de contenido",
                pagina.contains("action=\"/tareas\""));
        check("el contenido heredado queda dentro del <main> del layout",
                entre(pagina, "<main>", "</main>").contains("action=\"/tareas\""));
        check("y el layout mantiene su pie fuera del bloque",
                entre(pagina, "<header>", "</header>").contains("servido por Cero"));
        check("no quedan directivas de plantilla sin resolver",
                !pagina.contains("{%") && !pagina.contains("{{"));
    }

    private static void cabecerasDeSeguridad() {
        TestResponse portada = cliente().get("/");
        check("la portada declara nosniff",
                "nosniff".equals(portada.header("x-content-type-options")));
        check("y prohíbe el enmarcado", "DENY".equals(portada.header("x-frame-options")));
        check("con política de referente",
                "strict-origin-when-cross-origin".equals(portada.header("referrer-policy")));
        check("cámara, micrófono y ubicación cerradas",
                cabecera(portada, "permissions-policy").contains("camera=()"));
        check("y una CSP que solo permite el propio origen",
                "default-src 'self'".equals(portada.header("content-security-policy")));
        check("sin TLS no se manda HSTS", portada.header("strict-transport-security") == null);

        TestResponse rechazo = cliente().form("/tareas", Map.of("titulo", "intruso"));
        check("y también viajan en una respuesta rechazada",
                rechazo.status() == 403 && rechazo.header("x-content-type-options") != null);
    }

    /**
     * Las cabeceras tienen que llegar al documento HTML, que es donde el clickjacking ocurre.
     *
     * <p>El fallback de estáticos se saltaba la cadena entera de middlewares, así que sus
     * respuestas —incluido el 404— salían desnudas.
     */
    private static void cabecerasEnElDocumentoHtml() {
        TestResponse html = cliente().get("/");
        check("el documento HTML viaja con X-Frame-Options",
                tipo(html).startsWith("text/html") && "DENY".equals(html.header("x-frame-options")));
        check("y con su CSP",
                "default-src 'self'".equals(html.header("content-security-policy")));

        TestResponse ausente = cliente().get("/estaticos/no-esta.css");
        check("un estático inexistente da 404", ausente.status() == 404);
        check("y ese 404 pasa por la cadena de middlewares",
                "DENY".equals(ausente.header("x-frame-options"))
                        && "nosniff".equals(ausente.header("x-content-type-options"))
                        && "default-src 'self'".equals(ausente.header("content-security-policy")));

        TestResponse servido = cliente().get("/estaticos/estilo.css");
        check("el estático que sí existe también las lleva",
                servido.status() == 200 && "DENY".equals(servido.header("x-frame-options")));

        TestResponse ruta = cliente().get("/no-existe");
        check("una ruta inexistente da 404", ruta.status() == 404);
        check("con sus cabeceras de seguridad",
                "DENY".equals(ruta.header("x-frame-options"))
                        && "nosniff".equals(ruta.header("x-content-type-options")));

        check("y la API las lleva igual que el HTML",
                "DENY".equals(cliente().get("/api/tareas").header("x-frame-options")));
    }

    /** Un 403 no puede ser una página en blanco: tiene que decir qué se rechazó y por qué. */
    private static void prohibido() {
        TestResponse prohibido = cliente().form("/tareas", Map.of("titulo", "intruso"));
        check("un alta sin sesión da 403", prohibido.status() == 403);
        check("nombrando el estado", "Forbidden".equals(prohibido.json("error")));
        check("y explicando el motivo",
                String.valueOf(prohibido.json("message")).contains("CSRF"));
        check("y la ruta rechazada", "/tareas".equals(prohibido.json("path")));
    }

    private static void altaPorFormulario() {
        TestClient cliente = cliente();
        String token = token(cliente.get("/").body());

        TestResponse alta = cliente.form("/tareas",
                Map.of("_csrf", token, "titulo", "Terminar la web", "prioridad", "alta"));
        check("el alta redirige", alta.status() == 302);
        check("a la portada", "/".equals(alta.header("location")));

        String conTarea = cliente.get("/").body();
        check("la tarea aparece en la lista", conTarea.contains("Terminar la web"));
        check("con su prioridad", conTarea.contains("prioridad-alta"));
        check("y el contador sube", conTarea.contains("1 pendientes de 1"));
    }

    /** La lista pinta una fila por tarea, con sus datos, y no una sola fila con todo dentro. */
    private static void tablaDeTareas() {
        TestClient cliente = cliente();
        String token = token(cliente.get("/").body());
        cliente.form("/tareas",
                Map.of("_csrf", token, "titulo", "Revisar el informe", "prioridad", "baja"));
        cliente.form("/tareas",
                Map.of("_csrf", token, "titulo", "Llamar a la imprenta", "prioridad", "media"));

        String tabla = cliente.get("/").body();
        check("la lista pinta una fila por tarea", contar(tabla, "<li class=") == 3);
        check("con el título de cada una",
                tabla.contains("Terminar la web") && tabla.contains("Revisar el informe")
                        && tabla.contains("Llamar a la imprenta"));
        check("y la prioridad de cada una",
                tabla.contains("prioridad-alta") && tabla.contains("prioridad-baja")
                        && tabla.contains("prioridad-media"));
        check("cada fila trae su botón de borrar", contar(tabla, "class=\"borrar\"") == 3);
        check("el pie cuadra con las filas", tabla.contains("3 pendientes de 3"));
        check("y ya no se anuncia la lista vacía", !tabla.contains("No hay ninguna tarea"));
    }

    private static void csrf() {
        TestResponse sinToken = cliente().form("/tareas", Map.of("titulo", "intruso"));
        check("un POST sin token da 403", sinToken.status() == 403);

        TestClient cliente = cliente();
        cliente.get("/");
        TestResponse tokenMalo = cliente.form("/tareas",
                Map.of("_csrf", "inventado", "titulo", "intruso"));
        check("un POST con token falso da 403", tokenMalo.status() == 403);
        check("y la tarea no se creó", !cliente().get("/").body().contains("intruso"));
    }

    private static void validacionDeFormulario() {
        TestClient cliente = cliente();
        String token = token(cliente.get("/").body());
        TestResponse corto = cliente.form("/tareas", Map.of("_csrf", token, "titulo", "ab"));
        check("un título corto da 422", corto.status() == 422);
        check("y explica el motivo", corto.body().contains("debe tener entre 3 y 120 caracteres"));
        check("conservando lo escrito", corto.body().contains("value=\"ab\""));
    }

    private static void api() {
        TestClient cliente = cliente();
        TestResponse creada = cliente.post("/api/tareas",
                "{\"titulo\":\"Desde la API\",\"prioridad\":\"baja\"}");
        check("POST devuelve 201", creada.status() == 201);
        check("con Location", cabecera(creada, "location").startsWith("/api/tareas/"));
        check("y el recurso creado", creada.body().contains("\"titulo\":\"Desde la API\""));
        check("con el id asignado por la base de datos", !creada.body().contains("\"id\":0"));

        TestResponse invalida = cliente.post("/api/tareas",
                "{\"titulo\":\"x\",\"prioridad\":\"inventada\"}");
        check("un cuerpo inválido da 422", invalida.status() == 422);
        check("detallando los dos campos", invalida.body().contains("\"titulo\"")
                && invalida.body().contains("\"prioridad\""));

        String id = cabecera(creada, "location").replace("/api/tareas/", "");
        check("GET por id devuelve la tarea",
                cliente.get("/api/tareas/" + id).body().contains("Desde la API"));
        check("un id inexistente da 404", cliente.get("/api/tareas/9999").status() == 404);

        TestResponse lista = cliente.get("/api/tareas");
        check("la lista viene paginada", lista.body().contains("\"page\":1")
                && lista.body().contains("\"total\":"));

        check("DELETE devuelve 204", cliente.delete("/api/tareas/" + id).status() == 204);
        check("borrar lo inexistente da 404", cliente.delete("/api/tareas/9999").status() == 404);
    }

    private static void estaticos() {
        TestResponse css = cliente().get("/estaticos/estilo.css");
        check("sirve el CSS desde el classpath", css.status() == 200);
        check("con su tipo", tipo(css).startsWith("text/css"));
        check("y contenido", css.body().contains("--azul"));
        check("emite ETag", css.header("etag") != null);

        TestResponse cacheado = app.client().http1()
                .header("If-None-Match", css.header("etag"))
                .get("/estaticos/estilo.css");
        check("y responde 304 si no cambió", cacheado.status() == 304);
        check("un estático inexistente da 404",
                cliente().get("/estaticos/no-esta.css").status() == 404);
    }

    /** La cookie de sesión se perdía sobre HTTP/2: aquí se comprueba desde una app real. */
    private static void sesionSobreHttp2() {
        TestClient cliente = app.client().http2();
        TestResponse portada = cliente.get("/");
        check("la portada responde sobre HTTP/2",
                portada.status() == 200 && portada.version() == HttpClient.Version.HTTP_2);
        check("y abre sesión", cliente.cookie("CEROSESSION") != null);

        TestResponse alta = cliente.form("/tareas",
                Map.of("_csrf", token(portada.body()), "titulo", "Alta sobre HTTP/2"));
        check("la sesión sobrevive a la segunda petición sobre HTTP/2", alta.status() == 302);
        check("que también va por HTTP/2", alta.version() == HttpClient.Version.HTTP_2);
        check("y la tarea quedó creada", cliente.get("/").body().contains("Alta sobre HTTP/2"));

        check("un cliente nuevo sobre HTTP/2 no hereda la sesión",
                app.client().http2().form("/tareas", Map.of("titulo", "intruso")).status() == 403);
    }

    /** Un cliente nuevo por grupo: su propio tarro de cookies, o sea su propia sesión. */
    private static void cors() {
        String permitido = "https://tareas.local";

        TestResponse desdePermitido = app.client().http1()
                .header("Origin", permitido).get("/api/tareas");
        check("un origen permitido recibe allow-origin",
                permitido.equals(cabecera(desdePermitido, "access-control-allow-origin")));

        TestResponse desdeAjeno = app.client().http1()
                .header("Origin", "https://otro.example").get("/api/tareas");
        check("un origen ajeno no lo recibe",
                cabecera(desdeAjeno, "access-control-allow-origin").isEmpty());
        check("y la petición se atiende igual: CORS lo aplica el navegador, no el servidor",
                desdeAjeno.status() == 200);

        TestResponse sinOrigen = cliente().get("/api/tareas");
        check("sin cabecera Origin no se anuncia CORS",
                cabecera(sinOrigen, "access-control-allow-origin").isEmpty());
    }

    private static TestClient cliente() {
        return app.client().http1();
    }

    private static String token(String html) {
        Matcher matcher = CSRF.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String tipo(TestResponse response) {
        return cabecera(response, "content-type");
    }

    private static String cabecera(TestResponse response, String name) {
        String value = response.header(name);
        return value == null ? "" : value;
    }

    private static String entre(String texto, String desde, String hasta) {
        int inicio = texto.indexOf(desde);
        int fin = inicio < 0 ? -1 : texto.indexOf(hasta, inicio);
        return inicio < 0 || fin < 0 ? "" : texto.substring(inicio + desde.length(), fin);
    }

    private static int contar(String texto, String fragmento) {
        int veces = 0;
        for (int i = texto.indexOf(fragmento); i >= 0; i = texto.indexOf(fragmento, i + 1)) {
            veces++;
        }
        return veces;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  OK  " + name);
        } else {
            failed++;
            System.out.println("  XX  " + name);
        }
    }
}
