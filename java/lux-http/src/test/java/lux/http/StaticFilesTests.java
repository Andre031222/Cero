package lux.http;

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
        tipos();

        Files.deleteIfExists(fuera);
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
