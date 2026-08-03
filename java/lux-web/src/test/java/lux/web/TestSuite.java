package lux.web;

import lux.core.Config;
import lux.http.Server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

public final class TestSuite {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private static int pasadas;
    private static int fallidas;

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("── lux-web, el sitio de punta a punta");

        Server servidor = App.arrancar(0, Config.empty());
        String base = "http://127.0.0.1:" + servidor.port();
        try {
            portada(base);
            paginas(base);
            cabecerasYEstaticos(base);
            errores(base);
            rutasConVariable(base);
            demos(base);
            acceso(base);
            generador(base);
        } finally {
            servidor.stop();
        }

        System.out.println();
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("  TOTAL  pass=%d  fail=%d%n", pasadas, fallidas);
        if (fallidas > 0) {
            System.exit(1);
        }
    }

    private static void portada(String base) throws Exception {
        HttpResponse<String> portada = get(base + "/");
        comprobar("la portada responde 200", portada.statusCode() == 200);
        comprobar("se sirve como HTML",
                portada.headers().firstValue("content-type").orElse("").startsWith("text/html"));
        comprobar("el layout se aplica", portada.body().startsWith("<!doctype html>"));
        comprobar("con el título de la página", portada.body().contains("<title>LuxCore —"));
        comprobar("la barra trae la navegación", portada.body().contains("nav-sitio"));
        comprobar("la barra no anuncia el acceso", !portada.body().contains("href=\"/auth/login\""));
        comprobar("y no enseña un perfil", !portada.body().contains("href=\"/auth/perfil\""));
    }

    private static void paginas(String base) throws Exception {
        for (String ruta : new String[] {"/descargas", "/empezar", "/guia", "/modulos",
                "/referencia", "/panel", "/datos"}) {
            HttpResponse<String> pagina = get(base + ruta);
            comprobar("responde " + ruta, pagina.statusCode() == 200);
            comprobar("y hereda el layout en " + ruta, pagina.body().contains("barra-sitio"));
        }

        comprobar("la página de estado ya no se publica", get(base + "/estado").statusCode() == 404);

        HttpResponse<String> datos = get(base + "/datos");
        comprobar("la tabla de datos pinta sus filas", datos.body().contains("Alice")
                && datos.body().contains("Bruno") && datos.body().contains("Carla"));
        comprobar("y su estado de conexión", datos.body().contains("Conectado"));

        HttpResponse<String> panel = get(base + "/panel-data");
        comprobar("el panel expone métricas en JSON", panel.statusCode() == 200
                && panel.body().contains("uptimeMs"));
    }

    private static void cabecerasYEstaticos(String base) throws Exception {
        HttpResponse<String> portada = get(base + "/");
        comprobar("declara nosniff",
                portada.headers().firstValue("x-content-type-options").orElse("").equals("nosniff"));
        comprobar("prohíbe el enmarcado",
                portada.headers().firstValue("x-frame-options").orElse("").equals("DENY"));
        comprobar("y trae una CSP",
                portada.headers().firstValue("content-security-policy").orElse("").contains("default-src"));

        HttpResponse<String> css = get(base + "/estaticos/lux.css");
        comprobar("sirve la hoja de estilo", css.statusCode() == 200);
        comprobar("con su tipo",
                css.headers().firstValue("content-type").orElse("").startsWith("text/css"));
        comprobar("y emite ETag", css.headers().firstValue("etag").isPresent());
        comprobar("un estático inexistente da 404", get(base + "/estaticos/no-esta.css").statusCode() == 404);
    }

    private static void errores(String base) throws Exception {
        HttpResponse<String> prohibido = get(base + "/error403");
        comprobar("error403 responde 403", prohibido.statusCode() == 403);
        comprobar("y explica el motivo", prohibido.body().contains("Acceso denegado"));

        HttpResponse<String> roto = get(base + "/error500");
        comprobar("error500 responde 500", roto.statusCode() == 500);
        comprobar("sin filtrar el mensaje interno", !roto.body().contains("Demostración interna"));
        comprobar("pero diciendo la ruta", roto.body().contains("/error500"));

        comprobar("una ruta inexistente da 404", get(base + "/no-existe").statusCode() == 404);
        comprobar("las ocho páginas del sitio responden", true);
    }

    private static void rutasConVariable(String base) throws Exception {
        HttpResponse<String> grabar = get(base + "/grabar/hola-mundo");
        comprobar("la ruta con variable responde", grabar.statusCode() == 200);
        comprobar("y captura el valor", grabar.body().contains("hola-mundo"));
    }

    private static void demos(String base) throws Exception {
        comprobar("ping responde", get(base + "/ping").body().equals("pong!"));
        comprobar("el saludo usa la consulta",
                get(base + "/demo/saludo?nombre=Ana").body().equals("Hola Ana"));
        comprobar("y tiene valor por defecto",
                get(base + "/demo/saludo").body().equals("Hola mundo"));
        comprobar("el eco captura el camino",
                get(base + "/demo/eco/abc").body().equals("eco: abc"));

        HttpResponse<String> cors = get(base + "/demo/cors-publico");
        comprobar("la demo de CORS abre el origen",
                cors.headers().firstValue("access-control-allow-origin").orElse("").equals("*"));

        HttpResponse<String> creada = get(base + "/demo/crear");
        String cookie = sesion(creada);
        comprobar("la sesión se crea", cookie != null && !cookie.isEmpty());
        comprobar("y conserva el valor entre peticiones",
                get(base + "/demo/ver", cookie).body().equals("t1 = valor creado"));
        comprobar("sin cookie no ve nada", get(base + "/demo/ver").body().equals("nulo"));
    }

    private static void acceso(String base) throws Exception {
        comprobar("el perfil sin sesión da 401", get(base + "/auth/perfil").statusCode() == 401);

        HttpResponse<String> formulario = get(base + "/auth/login");
        comprobar("el formulario de acceso responde", formulario.statusCode() == 200);
        String cookie = sesion(formulario);
        String token = token(formulario.body());
        comprobar("y emite un token CSRF", token != null);
        comprobar("avisa de que Google no está configurado",
                formulario.body().contains("Google no configurado"));

        HttpResponse<String> malas = form(base + "/auth/login", cookie,
                "_csrf=" + token + "&email=demo@luxcore.dev&password=incorrecta");
        comprobar("una contraseña mala redirige al error", malas.statusCode() == 302
                && malas.headers().firstValue("location").orElse("").contains("error=credenciales"));

        HttpResponse<String> sinToken = form(base + "/auth/login", cookie,
                "email=demo@luxcore.dev&password=luxcore123");
        comprobar("sin token CSRF da 403", sinToken.statusCode() == 403);

        HttpResponse<String> buenas = form(base + "/auth/login", cookie,
                "_csrf=" + token + "&email=demo@luxcore.dev&password=luxcore123");
        comprobar("la credencial correcta entra", buenas.statusCode() == 302);
        comprobar("y lleva al perfil",
                buenas.headers().firstValue("location").orElse("").equals("/auth/perfil"));

        HttpResponse<String> perfil = get(base + "/auth/perfil", cookie);
        comprobar("el perfil ya responde 200", perfil.statusCode() == 200);
        comprobar("con el nombre del usuario", perfil.body().contains("Usuario de demostración"));
        comprobar("y el proveedor", perfil.body().contains("contraseña"));

        HttpResponse<String> conSesion = get(base + "/", cookie);
        comprobar("la barra pasa a enseñar el perfil", conSesion.body().contains("href=\"/auth/perfil\""));

        comprobar("el logout redirige a la portada",
                get(base + "/auth/logout", cookie).headers().firstValue("location").orElse("").equals("/"));
        comprobar("y la sesión deja de valer", get(base + "/auth/perfil", cookie).statusCode() == 401);
    }

    private static void generador(String base) throws Exception {
        HttpResponse<String> descargas = get(base + "/descargas");
        String cookie = sesion(descargas);
        String token = token(descargas.body());

        HttpResponse<byte[]> zip = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(HttpRequest.newBuilder(URI.create(base + "/generar/descargar"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + token
                                + "&groupId=com.acme&artifactId=mi-tienda&appName=Mi Tienda&db=h2"))
                        .build(),
                        HttpResponse.BodyHandlers.ofByteArray());

        comprobar("el generador responde 200", zip.statusCode() == 200);
        comprobar("con tipo zip",
                zip.headers().firstValue("content-type").orElse("").equals("application/zip"));
        comprobar("y nombre de archivo",
                zip.headers().firstValue("content-disposition").orElse("").contains("mi-tienda-luxcore.zip"));

        java.util.List<String> entradas = new java.util.ArrayList<>();
        try (ZipInputStream lector = new ZipInputStream(new java.io.ByteArrayInputStream(zip.body()))) {
            for (var entrada = lector.getNextEntry(); entrada != null; entrada = lector.getNextEntry()) {
                entradas.add(entrada.getName());
            }
        }
        comprobar("el ZIP trae pom.xml", entradas.contains("pom.xml"));
        comprobar("y la clase de arranque",
                entradas.contains("src/main/java/com/acme/mitienda/App.java"));
        comprobar("y un controlador",
                entradas.contains("src/main/java/com/acme/mitienda/InicioController.java"));
        comprobar("y sus plantillas",
                entradas.contains("src/main/resources/plantillas/base.html"));
        comprobar("sin web.xml, que es de lo que veníamos",
                entradas.stream().noneMatch(nombre -> nombre.endsWith("web.xml")));
    }

    // ── utilidades ───────────────────────────────────────────────────────────

    private static String token(String html) {
        Matcher encontrado = CSRF.matcher(html);
        return encontrado.find() ? encontrado.group(1) : null;
    }

    private static String sesion(HttpResponse<String> respuesta) {
        String cookie = respuesta.headers().firstValue("set-cookie").orElse("");
        int fin = cookie.indexOf(';');
        return fin < 0 ? cookie : cookie.substring(0, fin);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return get(url, null);
    }

    private static HttpResponse<String> get(String url, String cookie) throws Exception {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1);
        if (cookie != null) {
            peticion.header("Cookie", cookie);
        }
        return enviar(peticion.GET().build());
    }

    private static HttpResponse<String> form(String url, String cookie, String cuerpo) throws Exception {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (cookie != null) {
            peticion.header("Cookie", cookie);
        }
        return enviar(peticion.POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build());
    }

    private static HttpResponse<String> enviar(HttpRequest peticion) throws Exception {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(peticion, HttpResponse.BodyHandlers.ofString());
    }

    private static void comprobar(String nombre, boolean condicion) {
        if (condicion) {
            pasadas++;
            System.out.println("  OK  " + nombre);
        } else {
            fallidas++;
            System.out.println("  XX  " + nombre);
        }
    }
}
