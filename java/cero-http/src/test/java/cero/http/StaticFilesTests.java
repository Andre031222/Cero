package cero.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

final class StaticFilesTests {

    private StaticFilesTests() {
    }

    static void run() throws Exception {
        Check.group("archivos estáticos");

        Path root = Files.createTempDirectory("lux-static");
        Files.writeString(root.resolve("index.html"), "<h1>inicio</h1>");
        Files.writeString(root.resolve("estilo.css"), "body{margin:0}");
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("sub").resolve("dato.json"), "{\"a\":1}");

        Path fuera = root.getParent().resolve("secreto-" + root.getFileName() + ".txt");
        Files.writeString(fuera, "no debe verse");

        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                StaticFiles.from(root), ErrorReporter.silent())) {
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> css = Fixture.get(base + "/estilo.css");
            Check.equal("sirve el archivo", css.body(), "body{margin:0}");
            Check.equal("con el Content-Type correcto",
                    css.headers().firstValue("content-type").orElse(null), "text/css; charset=utf-8");
            Check.that("emite ETag", css.headers().firstValue("etag").isPresent());
            Check.that("emite Last-Modified", css.headers().firstValue("last-modified").isPresent());

            Check.equal("la raíz sirve el índice", Fixture.get(base + "/").body(), "<h1>inicio</h1>");
            Check.equal("sirve subdirectorios", Fixture.get(base + "/sub/dato.json").body(), "{\"a\":1}");
            Check.equal("json con su tipo",
                    Fixture.get(base + "/sub/dato.json").headers().firstValue("content-type").orElse(null),
                    "application/json");

            String etag = css.headers().firstValue("etag").orElseThrow();
            HttpResponse<String> cacheado = Fixture.send(
                    HttpRequest.newBuilder(URI.create(base + "/estilo.css"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("If-None-Match", etag).build());
            Check.equal("If-None-Match devuelve 304", cacheado.statusCode(), 304);
            Check.that("el 304 no lleva cuerpo", cacheado.body().isEmpty());

            Check.equal("archivo inexistente da 404", Fixture.get(base + "/no-esta.txt").statusCode(), 404);

            Check.equal("path traversal con ../ se bloquea",
                    Fixture.get(base + "/../" + fuera.getFileName()).statusCode(), 404);
            Check.that("path traversal codificado se bloquea",
                    Fixture.raw(server.port(), "GET /%2e%2e/" + fuera.getFileName()
                            + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").contains(" 404 "));
            Check.that("el contenido de fuera nunca aparece",
                    !Fixture.raw(server.port(), "GET /../" + fuera.getFileName()
                            + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").contains("no debe verse"));

            HttpResponse<String> post = Fixture.send(
                    HttpRequest.newBuilder(URI.create(base + "/estilo.css"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .POST(HttpRequest.BodyPublishers.noBody()).build());
            Check.equal("POST sobre estáticos da 405", post.statusCode(), 405);
            Check.equal("y anuncia los métodos permitidos",
                    post.headers().firstValue("allow").orElse(null), "GET, HEAD");
        }

        prefijo(root);
        rangos();
        tipos();
        cacheDelClasspath();
        spa();

        Files.deleteIfExists(fuera);
    }

    /**
     * El caché de recursos del classpath se llenaba con las rutas que NO existen —dos entradas
     * por cada 404, con la ruta pedida como clave—, así que cualquiera podía agotar el montón
     * pidiendo direcciones inventadas. Aquí se piden 400 y se exige que el caché siga vacío.
     */
    private static void cacheDelClasspath() throws Exception {
        StaticFiles estaticos = StaticFiles.fromClasspath("estaticos-prueba");
        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                estaticos, ErrorReporter.silent())) {
            String base = "http://127.0.0.1:" + server.port();

            Check.equal("una ruta inexistente da 404",
                    Fixture.get(base + "/no-existe.css").statusCode(), 404);

            // Una exportación estática pone cada página en su carpeta: /seccion es
            // seccion/index.html. Un directorio del classpath "existe" y leerlo da cero bytes,
            // así que sin cuidado se respondía 200 con el cuerpo vacío.
            var seccion = Fixture.get(base + "/seccion");
            Check.equal("un directorio sirve su index.html", seccion.statusCode(), 200);
            Check.equal("y con su contenido, no vacío", seccion.body(), "<h1>seccion</h1>");
            Check.that("con el tipo de HTML",
                    seccion.headers().firstValue("content-type").orElse("").startsWith("text/html"));

            int antes = estaticos.cacheados();
            for (int i = 0; i < 400; i++) {
                Fixture.get(base + "/inventada-" + i + ".css");
            }
            Check.equal("y 400 peticiones a rutas inventadas no añaden nada al caché",
                    estaticos.cacheados(), antes);
        }
    }

    /**
     * Una aplicación que enruta en el cliente —React, Svelte— no tiene un archivo por ruta. Sin
     * respaldo, /nosotros da 404 y la aplicación solo funciona entrando por la portada. Con él,
     * devuelve index.html… pero solo para rutas de página: un .css que falte tiene que seguir
     * dando 404, o un error evidente se convierte en uno de una hora.
     */
    private static void spa() throws Exception {
        Path raiz = Files.createTempDirectory("lux-spa");
        Files.writeString(raiz.resolve("index.html"), "<div id=app></div>");
        Files.createDirectory(raiz.resolve("assets"));
        Files.writeString(raiz.resolve("assets").resolve("app.js"), "console.log(1)");

        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                StaticFiles.from(raiz).spa(), ErrorReporter.silent())) {
            String base = "http://127.0.0.1:" + server.port();

            Check.equal("la portada se sirve", Fixture.get(base + "/").body(), "<div id=app></div>");
            Check.equal("y sus recursos", Fixture.get(base + "/assets/app.js").body(), "console.log(1)");

            HttpResponse<String> ruta = Fixture.get(base + "/nosotros");
            Check.equal("una ruta de cliente devuelve 200", ruta.statusCode(), 200);
            Check.equal("con el HTML de la portada", ruta.body(), "<div id=app></div>");

            HttpResponse<String> anidada = Fixture.get(base + "/panel/usuarios/7");
            Check.equal("también si va anidada", anidada.statusCode(), 200);

            Check.equal("pero un recurso que falta sigue dando 404",
                    Fixture.get(base + "/assets/no-esta.css").statusCode(), 404);
            Check.equal("y una imagen que falta también",
                    Fixture.get(base + "/logo.png").statusCode(), 404);
        }

        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                StaticFiles.from(raiz), ErrorReporter.silent())) {
            Check.equal("sin spa() activado, la ruta de cliente da 404",
                    Fixture.get("http://127.0.0.1:" + server.port() + "/nosotros").statusCode(), 404);
        }
    }

    private static void prefijo(Path root) throws Exception {
        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                StaticFiles.from(root, "/assets"), ErrorReporter.silent())) {
            String base = "http://127.0.0.1:" + server.port();
            Check.equal("con prefijo sirve bajo la ruta",
                    Fixture.get(base + "/assets/estilo.css").body(), "body{margin:0}");
            Check.equal("fuera del prefijo da 404",
                    Fixture.get(base + "/estilo.css").statusCode(), 404);
        }
    }

    private static void rangos() throws Exception {
        Path root = Files.createTempDirectory("lux-rangos");
        Files.writeString(root.resolve("alfabeto.txt"), "ABCDEFGHIJKLMNOPQRSTUVWXYZ");

        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                StaticFiles.from(root), ErrorReporter.silent())) {
            String base = "http://127.0.0.1:" + server.port() + "/alfabeto.txt";

            HttpResponse<String> entero = Fixture.get(base);
            Check.equal("una respuesta entera anuncia que admite rangos",
                    entero.headers().firstValue("accept-ranges").orElse(null), "bytes");
            String etag = entero.headers().firstValue("etag").orElseThrow();

            HttpResponse<String> trozo = conRango(base, "bytes=0-4", null);
            Check.equal("un rango devuelve 206", trozo.statusCode(), 206);
            Check.equal("con los bytes pedidos", trozo.body(), "ABCDE");
            Check.equal("y el Content-Range",
                    trozo.headers().firstValue("content-range").orElse(null), "bytes 0-4/26");

            Check.equal("desde un punto hasta el final", conRango(base, "bytes=23-", null).body(), "XYZ");
            Check.equal("los últimos bytes", conRango(base, "bytes=-3", null).body(), "XYZ");
            Check.equal("un solo byte", conRango(base, "bytes=13-13", null).body(), "N");
            Check.equal("un final que se pasa se recorta",
                    conRango(base, "bytes=24-99", null).headers().firstValue("content-range").orElse(null),
                    "bytes 24-25/26");

            HttpResponse<String> fuera = conRango(base, "bytes=99-120", null);
            Check.equal("empezar más allá del final da 416", fuera.statusCode(), 416);
            Check.equal("diciendo el tamaño real",
                    fuera.headers().firstValue("content-range").orElse(null), "bytes */26");

            Check.equal("un rango invertido también da 416",
                    conRango(base, "bytes=10-2", null).statusCode(), 416);
            Check.equal("una cabecera que no se entiende sirve el recurso entero",
                    conRango(base, "elementos=0-4", null).statusCode(), 200);
            Check.equal("varios intervalos también sirven el recurso entero",
                    conRango(base, "bytes=0-1,5-6", null).statusCode(), 200);

            Check.equal("If-Range que coincide devuelve el trozo",
                    conRango(base, "bytes=0-2", etag).statusCode(), 206);
            Check.equal("If-Range que no coincide devuelve el recurso entero",
                    conRango(base, "bytes=0-2", "\"otro\"").statusCode(), 200);
        }
    }

    private static HttpResponse<String> conRango(String url, String rango, String ifRange)
            throws Exception {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Range", rango);
        if (ifRange != null) {
            peticion.header("If-Range", ifRange);
        }
        return Fixture.send(peticion.build());
    }

    private static void tipos() {
        Check.equal("html", MimeTypes.of("a.html"), "text/html; charset=utf-8");
        Check.equal("js", MimeTypes.of("a.js"), "application/javascript; charset=utf-8");
        Check.equal("png", MimeTypes.of("a.png"), "image/png");
        Check.equal("svg", MimeTypes.of("a.svg"), "image/svg+xml");
        Check.equal("mayúsculas", MimeTypes.of("A.PNG"), "image/png");
        Check.equal("sin extensión", MimeTypes.of("LICENSE"), "application/octet-stream");
        Check.equal("extensión desconocida", MimeTypes.of("a.xyz"), "application/octet-stream");
    }
}
