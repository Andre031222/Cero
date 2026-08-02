package lux.http;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

final class GzipTests {

    private GzipTests() {
    }

    static void run() throws Exception {
        Check.group("compresión gzip");

        String largo = "texto repetido para comprimir. ".repeat(200);

        Handler handler = (req, res) -> {
            switch (req.path()) {
                case "/largo" -> res.text(largo);
                case "/corto" -> res.text("poco");
                case "/binario" -> {
                    res.type("application/octet-stream");
                    res.send(largo.getBytes(StandardCharsets.UTF_8));
                }
                case "/json" -> res.json("{\"dato\":\"" + largo + "\"}");
                default -> res.status(404).text("404");
            }
        };

        try (Server server = Server.start(ServerOptions.builder().port(0).gzipMinBytes(1_024).build(),
                handler, ErrorReporter.silent())) {
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<byte[]> comprimido = fetch(base + "/largo", true);
            Check.equal("respuesta larga se comprime",
                    comprimido.headers().firstValue("content-encoding").orElse(null), "gzip");
            Check.equal("declara Vary: Accept-Encoding",
                    comprimido.headers().firstValue("vary").orElse(null), "Accept-Encoding");
            Check.equal("el contenido descomprimido coincide", inflate(comprimido.body()), largo);
            Check.that("comprimido pesa menos que el original",
                    comprimido.body().length < largo.getBytes(StandardCharsets.UTF_8).length);

            HttpResponse<byte[]> sinPedir = fetch(base + "/largo", false);
            Check.that("sin Accept-Encoding no se comprime",
                    sinPedir.headers().firstValue("content-encoding").isEmpty());
            Check.equal("y llega íntegro",
                    new String(sinPedir.body(), StandardCharsets.UTF_8), largo);

            Check.that("respuesta corta no se comprime",
                    fetch(base + "/corto", true).headers().firstValue("content-encoding").isEmpty());
            Check.that("tipo binario no se comprime",
                    fetch(base + "/binario", true).headers().firstValue("content-encoding").isEmpty());
            Check.equal("json sí se comprime",
                    fetch(base + "/json", true).headers().firstValue("content-encoding").orElse(null), "gzip");
        }

        try (Server server = Server.start(ServerOptions.builder().port(0).gzipMinBytes(0).build(),
                handler, ErrorReporter.silent())) {
            Check.that("gzipMinBytes(0) desactiva la compresión",
                    fetch("http://127.0.0.1:" + server.port() + "/largo", true)
                            .headers().firstValue("content-encoding").isEmpty());
        }
    }

    private static HttpResponse<byte[]> fetch(String url, boolean acceptGzip) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1);
        if (acceptGzip) {
            request.header("Accept-Encoding", "gzip");
        }
        return Fixture.sendBytes(request.build());
    }

    private static String inflate(byte[] data) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
