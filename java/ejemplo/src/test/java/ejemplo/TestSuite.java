package ejemplo;

import corvo.data.DataSources;
import corvo.http.Server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TestSuite {

    private static final Pattern CSRF =
            Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private static int passed;
    private static int failed;

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("── aplicación de ejemplo, de punta a punta");

        Server server = App.start(0, "jdbc:h2:mem:pruebas;DB_CLOSE_DELAY=-1");
        String base = "http://127.0.0.1:" + server.port();
        try {
            paginaInicial(base);
            cabecerasDeSeguridad(base);
            altaPorFormulario(base);
            csrf(base);
            validacionDeFormulario(base);
            api(base);
            estaticos(base);
        } finally {
            server.stop();
            DataSources.clear();
        }

        System.out.println();
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("  TOTAL  pass=%d  fail=%d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void paginaInicial(String base) throws Exception {
        HttpResponse<String> portada = get(base + "/");
        check("la portada responde 200", portada.statusCode() == 200);
        check("se sirve como HTML",
                portada.headers().firstValue("content-type").orElse("").startsWith("text/html"));
        check("el layout se aplica", portada.body().startsWith("<!doctype html>"));
        check("el bloque de título se rellena", portada.body().contains("<title>Tareas · 0 pendientes"));
        check("sin tareas muestra el aviso", portada.body().contains("No hay ninguna tarea"));
        check("emite el token CSRF en el formulario", token(portada.body()) != null);
        check("abre sesión", portada.headers().firstValue("set-cookie").isPresent());
        check("/salud responde", get(base + "/salud").body().equals("ok"));
    }

    private static void cabecerasDeSeguridad(String base) throws Exception {
        HttpResponse<String> portada = get(base + "/");
        check("la portada declara nosniff",
                portada.headers().firstValue("x-content-type-options").orElse("").equals("nosniff"));
        check("y prohíbe el enmarcado",
                portada.headers().firstValue("x-frame-options").orElse("").equals("DENY"));
        check("con política de referente",
                portada.headers().firstValue("referrer-policy").orElse("")
                        .equals("strict-origin-when-cross-origin"));
        check("cámara, micrófono y ubicación cerradas",
                portada.headers().firstValue("permissions-policy").orElse("").contains("camera=()"));
        check("y una CSP que solo permite el propio origen",
                portada.headers().firstValue("content-security-policy").orElse("")
                        .equals("default-src 'self'"));
        check("sin TLS no se manda HSTS",
                portada.headers().firstValue("strict-transport-security").isEmpty());

        HttpResponse<String> rechazo = form(base + "/tareas", null, "titulo=intruso");
        check("y también viajan en una respuesta rechazada",
                rechazo.statusCode() == 403
                        && rechazo.headers().firstValue("x-content-type-options").isPresent());
    }

    private static void altaPorFormulario(String base) throws Exception {
        HttpResponse<String> portada = get(base + "/");
        String cookie = sesion(portada);
        String token = token(portada.body());

        HttpResponse<String> alta = form(base + "/tareas", cookie,
                "_csrf=" + token + "&titulo=Terminar+la+web&prioridad=alta");
        check("el alta redirige", alta.statusCode() == 302);
        check("a la portada", alta.headers().firstValue("location").orElse("").equals("/"));

        HttpResponse<String> conTarea = get(base + "/", cookie);
        check("la tarea aparece en la lista", conTarea.body().contains("Terminar la web"));
        check("con su prioridad", conTarea.body().contains("prioridad-alta"));
        check("y el contador sube", conTarea.body().contains("1 pendientes de 1"));
    }

    private static void csrf(String base) throws Exception {
        HttpResponse<String> sinToken = form(base + "/tareas", null, "titulo=intruso");
        check("un POST sin token da 403", sinToken.statusCode() == 403);

        HttpResponse<String> portada = get(base + "/");
        HttpResponse<String> tokenMalo = form(base + "/tareas", sesion(portada),
                "_csrf=inventado&titulo=intruso");
        check("un POST con token falso da 403", tokenMalo.statusCode() == 403);
        check("y la tarea no se creó", !get(base + "/").body().contains("intruso"));
    }

    private static void validacionDeFormulario(String base) throws Exception {
        HttpResponse<String> portada = get(base + "/");
        HttpResponse<String> corto = form(base + "/tareas", sesion(portada),
                "_csrf=" + token(portada.body()) + "&titulo=ab");
        check("un título corto da 422", corto.statusCode() == 422);
        check("y explica el motivo", corto.body().contains("debe tener entre 3 y 120 caracteres"));
        check("conservando lo escrito", corto.body().contains("value=\"ab\""));
    }

    private static void api(String base) throws Exception {
        HttpResponse<String> creada = json(base + "/api/tareas", "POST",
                "{\"titulo\":\"Desde la API\",\"prioridad\":\"baja\"}");
        check("POST devuelve 201", creada.statusCode() == 201);
        check("con Location", creada.headers().firstValue("location").orElse("").startsWith("/api/tareas/"));
        check("y el recurso creado", creada.body().contains("\"titulo\":\"Desde la API\""));
        check("con el id asignado por la base de datos", !creada.body().contains("\"id\":0"));

        HttpResponse<String> invalida = json(base + "/api/tareas", "POST",
                "{\"titulo\":\"x\",\"prioridad\":\"inventada\"}");
        check("un cuerpo inválido da 422", invalida.statusCode() == 422);
        check("detallando los dos campos", invalida.body().contains("\"titulo\"")
                && invalida.body().contains("\"prioridad\""));

        String id = creada.headers().firstValue("location").orElse("").replace("/api/tareas/", "");
        check("GET por id devuelve la tarea",
                get(base + "/api/tareas/" + id).body().contains("Desde la API"));
        check("un id inexistente da 404", get(base + "/api/tareas/9999").statusCode() == 404);

        HttpResponse<String> lista = get(base + "/api/tareas");
        check("la lista viene paginada", lista.body().contains("\"page\":1")
                && lista.body().contains("\"total\":"));

        check("DELETE devuelve 204",
                json(base + "/api/tareas/" + id, "DELETE", null).statusCode() == 204);
        check("borrar lo inexistente da 404",
                json(base + "/api/tareas/9999", "DELETE", null).statusCode() == 404);
    }

    private static void estaticos(String base) throws Exception {
        HttpResponse<String> css = get(base + "/estaticos/estilo.css");
        check("sirve el CSS desde el classpath", css.statusCode() == 200);
        check("con su tipo",
                css.headers().firstValue("content-type").orElse("").startsWith("text/css"));
        check("y contenido", css.body().contains("--azul"));
        check("emite ETag", css.headers().firstValue("etag").isPresent());

        HttpResponse<String> cacheado = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/estaticos/estilo.css"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .header("If-None-Match", css.headers().firstValue("etag").orElseThrow())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        check("y responde 304 si no cambió", cacheado.statusCode() == 304);
        check("un estático inexistente da 404",
                get(base + "/estaticos/no-esta.css").statusCode() == 404);
    }

    private static String token(String html) {
        Matcher matcher = CSRF.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String sesion(HttpResponse<String> response) {
        String cookie = response.headers().firstValue("set-cookie").orElse("");
        int end = cookie.indexOf(';');
        return end < 0 ? cookie : cookie.substring(0, end);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return get(url, null);
    }

    private static HttpResponse<String> get(String url, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1);
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return send(request.GET().build());
    }

    private static HttpResponse<String> form(String url, String cookie, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpResponse<String> json(String url, String verb, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json");
        request.method(verb, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
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
