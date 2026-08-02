package lux.http;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

final class MultipartTests {

    private MultipartTests() {
    }

    private static final String BOUNDARY = "----luxboundary";

    static void run() throws Exception {
        Check.group("multipart/form-data");

        Handler handler = (req, res) -> {
            switch (req.path()) {
                case "/subir" -> {
                    Part archivo = req.part("archivo");
                    res.text(req.parts().size() + "|" + req.field("titulo") + "|"
                            + archivo.filename() + "|" + archivo.contentType() + "|"
                            + archivo.size() + "|" + archivo.text());
                }
                case "/no-multipart" -> {
                    try {
                        req.parts();
                        res.text("no debería llegar");
                    } catch (HttpException expected) {
                        res.status(expected.status()).text("rechazado");
                    }
                }
                default -> res.status(404).text("404");
            }
        };

        try (Server server = Server.start(ServerOptions.builder().port(0).build(), handler,
                ErrorReporter.silent())) {
            String base = "http://127.0.0.1:" + server.port();

            byte[] cuerpo = build(
                    field("titulo", "informe anual"),
                    file("archivo", "datos.csv", "text/csv", "a,b\n1,2\n"));

            HttpResponse<String> response = Fixture.send(
                    HttpRequest.newBuilder(URI.create(base + "/subir"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo))
                            .build());

            Check.equal("multipart con campo y archivo",
                    response.body(), "2|informe anual|datos.csv|text/csv|8|a,b\n1,2\n");

            HttpResponse<String> plano = Fixture.send(
                    HttpRequest.newBuilder(URI.create(base + "/no-multipart"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("Content-Type", "text/plain")
                            .POST(HttpRequest.BodyPublishers.ofString("hola"))
                            .build());
            Check.equal("parts() sobre un cuerpo no multipart da 415", plano.statusCode(), 415);
        }

        parsingDirecto();
    }

    private static void parsingDirecto() {
        byte[] cuerpo = build(field("a", "1"), field("b", "dos"));
        var partes = Multipart.parse(cuerpo, "multipart/form-data; boundary=" + BOUNDARY, 16);
        Check.equal("parse devuelve todas las partes", partes.size(), 2);
        Check.equal("el nombre se extrae", partes.get(0).name(), "a");
        Check.equal("el valor se extrae", partes.get(1).text(), "dos");
        Check.that("un campo no es archivo", !partes.get(0).isFile());

        Check.that("boundary entre comillas se acepta",
                Multipart.parse(cuerpo, "multipart/form-data; boundary=\"" + BOUNDARY + "\"", 16).size() == 2);

        Check.that("sin boundary se rechaza",
                rejects(() -> Multipart.parse(cuerpo, "multipart/form-data", 16)));
        Check.that("boundary que no aparece se rechaza",
                rejects(() -> Multipart.parse(cuerpo, "multipart/form-data; boundary=otro", 16)));
        Check.that("exceder el número de partes se rechaza",
                rejects(() -> Multipart.parse(cuerpo, "multipart/form-data; boundary=" + BOUNDARY, 1)));
        Check.that("applies reconoce el tipo",
                Multipart.applies("multipart/form-data; boundary=x") && !Multipart.applies("text/plain"));
    }

    private static byte[] field(String name, String value) {
        return ("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] file(String name, String filename, String type, String content) {
        return ("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n" + content).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] build(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (byte[] part : parts) {
                out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(part);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            }
            out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        } catch (Exception cause) {
            throw new IllegalStateException(cause);
        }
        return out.toByteArray();
    }

    private static boolean rejects(Runnable action) {
        try {
            action.run();
            return false;
        } catch (HttpException expected) {
            return true;
        }
    }
}
