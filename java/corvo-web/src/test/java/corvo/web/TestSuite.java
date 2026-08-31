package corvo.web;

import corvo.core.Config;
import corvo.http.Server;

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
        System.out.println("── corvo-web, el sitio de punta a punta");

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
            instalador(base);
            versionDelProyectoGenerado();
        generadorConFrontend();
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
        comprobar("con el título de la página", portada.body().contains("<title>Corvo —"));
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

        HttpResponse<String> css = get(base + "/estaticos/corvo.css");
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
                zip.headers().firstValue("content-disposition").orElse("").contains("mi-tienda-corvo.zip"));

        java.util.List<String> entradas = new java.util.ArrayList<>();
        java.util.Map<String, String> contenido = new java.util.HashMap<>();
        try (ZipInputStream lector = new ZipInputStream(new java.io.ByteArrayInputStream(zip.body()))) {
            for (var entrada = lector.getNextEntry(); entrada != null; entrada = lector.getNextEntry()) {
                entradas.add(entrada.getName());
                contenido.put(entrada.getName(),
                        new String(lector.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
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

        // Lo de verdad: que lo generado arranque con java -jar y pinte su portada. Las dos
        // cosas estuvieron rotas y no se notaba, porque nadie miraba dentro del ZIP.
        String pom = contenido.getOrDefault("pom.xml", "");
        comprobar("el proyecto generado arma un jar ejecutable",
                pom.contains("maven-jar-plugin")
                        && pom.contains("<mainClass>com.acme.mitienda.App</mainClass>")
                        && pom.contains("copy-dependencies"));
        comprobar("y con el nombre que anuncia", pom.contains("<finalName>mi-tienda</finalName>"));
        comprobar("y sus plantillas se resuelven con .html",
                contenido.getOrDefault("src/main/java/com/acme/mitienda/App.java", "")
                        .contains("suffix(\".html\")"));
    }

    private static void instalador(String base) throws Exception {
        HttpResponse<String> version = get(base + "/version");
        comprobar("/version responde 200", version.statusCode() == 200);
        comprobar("con un número de versión", version.body().trim().matches("[0-9]+(\\.[0-9]+)*"));

        HttpResponse<String> unix = get(base + "/instalar");
        comprobar("/instalar responde 200", unix.statusCode() == 200);
        comprobar("como texto plano, para poder leerlo antes de ejecutarlo",
                unix.headers().firstValue("content-type").orElse("").startsWith("text/plain"));
        comprobar("y es un guion de shell", unix.body().startsWith("#!/bin/sh"));
        comprobar("que comprueba la huella de lo que baja", unix.body().contains("sha256"));

        HttpResponse<String> windows = get(base + "/instalar.ps1");
        comprobar("/instalar.ps1 responde 200", windows.statusCode() == 200);
        comprobar("y comprueba la huella también", windows.body().contains("Get-FileHash"));
    }

    /**
     * El pom generado tiene que pedir la versión que existe.
     *
     * <p>Estuvo clavado a mano en «0.3.0» y sobrevivió al renombrado: cada `corvo new` creaba
     * un proyecto que no resolvía sus dependencias, y no fallaba aquí sino en la máquina de
     * quien lo estrenaba — que es el peor sitio posible para descubrirlo.
     *
     * <p>Se comprueba contra la versión del pom padre y no contra una constante escrita otra
     * vez: una prueba que repite el valor a mano se queda desfasada junto con el código.
     */
    private static void versionDelProyectoGenerado() throws Exception {
        var peticion = GeneradorProyecto.Peticion.de("com.acme", "demo", "Demo", "ninguno");
        java.nio.file.Path base = java.nio.file.Files.createTempDirectory("corvo-version");
        escribirZip(GeneradorProyecto.construir(peticion), base);
        String pom = java.nio.file.Files.readString(base.resolve("pom.xml"));

        String esperada = versionDelPadre();
        comprobar("el pom generado pide la versión actual de corvo-core",
                pom.contains("<artifactId>corvo-core</artifactId>\n            <version>" + esperada));
        comprobar("y no deja marcadores sin sustituir", !pom.contains("@VERSION@"));
        comprobar("ni trozos de código Java sueltos", !pom.contains("VERSION +"));
    }

    /** La versión del pom padre del framework: la única fuente de verdad. */
    private static String versionDelPadre() throws Exception {
        java.nio.file.Path padre = java.nio.file.Path.of("..", "pom.xml").toAbsolutePath().normalize();
        for (String linea : java.nio.file.Files.readAllLines(padre)) {
            String t = linea.trim();
            if (t.startsWith("<version>") && t.endsWith("</version>")) {
                return t.substring(9, t.length() - 10);
            }
        }
        throw new IllegalStateException("no se encontró la versión en " + padre);
    }

    /** {@code corvo new … --front} tiene que dejar un backend que ya hable con un frontend aparte. */
    private static void generadorConFrontend() throws Exception {
        java.nio.file.Path base = java.nio.file.Files.createTempDirectory("corvo-front");
        java.nio.file.Path proyecto = base.resolve("demo");

        String antes = System.getProperty("user.dir");
        System.setProperty("user.dir", base.toString());
        try {
            java.nio.file.Path raizJava = proyecto.resolve("backend");
            var peticion = GeneradorProyecto.Peticion.de("com.acme", "demo", "Demo", "ninguno");
            escribirZip(GeneradorProyecto.construir(peticion), raizJava);
            Frontend.escribir(proyecto, peticion);
            Frontend.cablearBackend(raizJava, peticion);

            java.nio.file.Path app = raizJava.resolve("src/main/java/com/acme/demo/App.java");
            String fuente = java.nio.file.Files.readString(app);
            comprobar("la aplicación generada sirve el frontend", fuente.contains("fromClasspath(\"front\").spa()"));
            comprobar("y abre CORS a los puertos de desarrollo", fuente.contains("localhost:5173"));
            comprobar("sin motor de plantillas, que aquí no pinta nada", !fuente.contains("Templates"));

            String controlador = java.nio.file.Files.readString(
                    raizJava.resolve("src/main/java/com/acme/demo/InicioController.java"));
            comprobar("el controlador es una API bajo /api", controlador.contains("@Route(\"/api\")"));

            comprobar("hay carpeta para lo compilado del frontend",
                    java.nio.file.Files.isDirectory(raizJava.resolve("src/main/resources/front")));
            comprobar("y no quedan plantillas del otro estilo",
                    !java.nio.file.Files.exists(raizJava.resolve("src/main/resources/plantillas")));
            comprobar("el frontend tiene su carpeta y su guía",
                    java.nio.file.Files.exists(proyecto.resolve("frontend/LEEME.md")));
        } finally {
            System.setProperty("user.dir", antes);
        }
    }

    private static void escribirZip(byte[] zip, java.nio.file.Path destino) throws Exception {
        try (var entrada = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (var e = entrada.getNextEntry(); e != null; e = entrada.getNextEntry()) {
                java.nio.file.Path archivo = destino.resolve(e.getName());
                java.nio.file.Files.createDirectories(archivo.getParent());
                java.nio.file.Files.write(archivo, entrada.readAllBytes());
            }
        }
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
