package lux.http;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

final class ProtocolTests {

    private ProtocolTests() {
    }

    static void run() throws Exception {
        Check.group("protocolo HTTP/1.1");

        ServerOptions options = ServerOptions.builder()
                .port(0)
                .maxBodyBytes(4_096)
                .idleTimeoutMillis(2_000)
                .build();

        try (Server server = Server.start(options, ProtocolTests::route, ErrorReporter.silent())) {
            int port = server.port();
            String base = "http://127.0.0.1:" + port;

            basics(base);
            queryParameters(base);
            bodyWithContentLength(base);
            bodyChunked(port);
            headResponse(base);
            streamedResponse(base);
            redirectResponse(base);
            handlerErrors(base);
            headerInjection(port);
            percentDecodedPath(base);
            keepAliveReuse(port);
            connectionClose(port);
            pipelinedBodyIsDrained(port);
            malformedRequests(port);
            limits(port);
        }
    }

    private static void route(Request request, Response response) throws Exception {
        switch (request.path()) {
            case "/" -> response.text("lux");
            case "/eco" -> response.text(request.bodyText());
            case "/consulta" -> response.text(request.query("a") + "|" + request.query("b"));
            case "/flujo" -> {
                try (OutputStream out = response.stream()) {
                    out.write("uno".getBytes(StandardCharsets.UTF_8));
                    out.write("dos".getBytes(StandardCharsets.UTF_8));
                }
            }
            case "/redir" -> response.redirect("/destino");
            case "/no-existe" -> throw new HttpException(404, "no encontrado");
            case "/revienta" -> throw new IllegalStateException("fallo del handler");
            case "/ruta con espacio" -> response.text("decodificado");
            case "/metodo" -> response.text(request.method().name());
            case "/ignora-cuerpo" -> response.text("ok");
            case "/inyeccion" -> {
                response.header("X-Eco", "valor\r\nX-Colado: si");
                response.text("no debería llegar");
            }
            default -> response.status(404).text("404");
        }
    }

    private static void basics(String base) throws Exception {
        HttpResponse<String> response = Fixture.get(base + "/");
        Check.equal("GET devuelve 200", response.statusCode(), 200);
        Check.equal("GET devuelve el cuerpo", response.body(), "lux");
        Check.equal("Content-Length correcto",
                response.headers().firstValue("content-length").orElse(null), "3");
        Check.that("Date presente", response.headers().firstValue("date").isPresent());
    }

    private static void queryParameters(String base) throws Exception {
        Check.equal("query decodifica valores",
                Fixture.get(base + "/consulta?a=uno&b=dos%20tres").body(), "uno|dos tres");
        Check.equal("query con + como espacio",
                Fixture.get(base + "/consulta?a=uno+dos&b=x").body(), "uno dos|x");
        Check.equal("query ausente devuelve null",
                Fixture.get(base + "/consulta?a=solo").body(), "solo|null");
    }

    private static void bodyWithContentLength(String base) throws Exception {
        HttpResponse<String> response = Fixture.send(
                HttpRequest.newBuilder(URI.create(base + "/eco"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .POST(HttpRequest.BodyPublishers.ofString("hola mundo"))
                        .build());
        Check.equal("POST con Content-Length", response.body(), "hola mundo");
    }

    private static void bodyChunked(int port) throws Exception {
        String response = Fixture.raw(port,
                "POST /eco HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "4\r\nhola\r\n2\r\n s\r\n0\r\n\r\n");
        Check.that("POST chunked se reensambla", response.endsWith("hola s"));

        String conExtension = Fixture.raw(port,
                "POST /eco HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "4;algo=1\r\nhola\r\n0\r\n\r\n");
        Check.that("chunk con extensión se acepta", conExtension.endsWith("hola"));

        String invalido = Fixture.raw(port,
                "POST /eco HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "zz\r\nhola\r\n0\r\n\r\n");
        Check.that("tamaño de chunk inválido da 400", invalido.startsWith("HTTP/1.1 400"));
    }

    private static void headResponse(String base) throws Exception {
        HttpResponse<String> response = Fixture.send(
                HttpRequest.newBuilder(URI.create(base + "/"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build());
        Check.that("HEAD sin cuerpo", response.body().isEmpty());
        Check.equal("HEAD conserva Content-Length",
                response.headers().firstValue("content-length").orElse(null), "3");
    }

    private static void streamedResponse(String base) throws Exception {
        HttpResponse<String> response = Fixture.get(base + "/flujo");
        Check.equal("stream() responde 200", response.statusCode(), 200);
        Check.equal("stream() concatena chunks", response.body(), "unodos");
        Check.equal("stream() usa chunked",
                response.headers().firstValue("transfer-encoding").orElse(null), "chunked");
    }

    private static void redirectResponse(String base) throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(HttpRequest.newBuilder(URI.create(base + "/redir")).build(),
                        HttpResponse.BodyHandlers.ofString());
        Check.equal("redirect devuelve 302", response.statusCode(), 302);
        Check.equal("redirect fija Location",
                response.headers().firstValue("location").orElse(null), "/destino");
    }

    private static void handlerErrors(String base) throws Exception {
        Check.equal("HttpException fija el estado", Fixture.get(base + "/no-existe").statusCode(), 404);
        Check.equal("excepción no controlada da 500", Fixture.get(base + "/revienta").statusCode(), 500);
        Check.equal("ruta desconocida da 404", Fixture.get(base + "/ninguna").statusCode(), 404);
    }

    private static void headerInjection(int port) throws Exception {
        String response = Fixture.raw(port,
                "GET /inyeccion HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        Check.that("CRLF en cabecera no se cuela", !response.contains("X-Colado"));
        Check.that("CRLF en cabecera da 500", response.startsWith("HTTP/1.1 500"));
    }

    private static void percentDecodedPath(String base) throws Exception {
        Check.equal("el path se decodifica",
                Fixture.get(base + "/ruta%20con%20espacio").body(), "decodificado");
    }

    private static void keepAliveReuse(int port) throws Exception {
        try (Socket socket = Fixture.connect(port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("GET /metodo HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String first = Fixture.readHead(in);
            Check.that("keep-alive: primera respuesta", first.contains("200 OK"));
            Check.that("keep-alive: anunciado", first.toLowerCase().contains("connection: keep-alive"));
            Fixture.readExactly(in, Fixture.contentLength(first));

            out.write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String second = Fixture.readHead(in);
            Check.that("keep-alive: segunda respuesta reusa la conexión", second.contains("200 OK"));
            Check.equal("keep-alive: cuerpo correcto",
                    Fixture.readExactly(in, Fixture.contentLength(second)), "lux");
        }
    }

    private static void connectionClose(int port) throws Exception {
        String response = Fixture.raw(port, "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        Check.that("Connection: close se anuncia", response.toLowerCase().contains("connection: close"));
        Check.that("Connection: close cierra el socket", response.endsWith("lux"));
    }

    private static void pipelinedBodyIsDrained(int port) throws Exception {
        try (Socket socket = Fixture.connect(port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(("POST /ignora-cuerpo HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhola!")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String first = Fixture.readHead(in);
            Fixture.readExactly(in, Fixture.contentLength(first));

            out.write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String second = Fixture.readHead(in);
            Check.that("cuerpo no leído se drena antes de la siguiente petición",
                    second.startsWith("HTTP/1.1 200"));
            Check.equal("la conexión sigue sincronizada",
                    Fixture.readExactly(in, Fixture.contentLength(second)), "lux");
        }
    }

    private static void malformedRequests(int port) throws Exception {
        Check.that("línea de petición sin espacios da 400",
                Fixture.raw(port, "GET\r\n\r\n").startsWith("HTTP/1.1 400"));
        Check.that("línea de petición sin versión da 400",
                Fixture.raw(port, "GET /\r\n\r\n").startsWith("HTTP/1.1 400"));
        Check.that("versión no soportada da 505",
                Fixture.raw(port, "GET / HTTP/2.0\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 505"));
        Check.that("método desconocido da 501",
                Fixture.raw(port, "VOLAR / HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 501"));
        Check.that("destino sin barra inicial da 400",
                Fixture.raw(port, "GET http://x/ HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 400"));
        Check.that("cabecera plegada se rechaza",
                Fixture.raw(port, "GET / HTTP/1.1\r\nHost: x\r\nX-A: uno\r\n  dos\r\n\r\n")
                        .startsWith("HTTP/1.1 400"));
        Check.that("Content-Length duplicado se rechaza",
                Fixture.raw(port, "POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\na")
                        .startsWith("HTTP/1.1 400"));
        Check.that("Content-Length junto a Transfer-Encoding se rechaza",
                Fixture.raw(port, "POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n").startsWith("HTTP/1.1 400"));
        Check.that("nombre de cabecera inválido se rechaza",
                Fixture.raw(port, "GET / HTTP/1.1\r\nHost: x\r\nX Mal: uno\r\n\r\n")
                        .startsWith("HTTP/1.1 400"));
    }

    private static void limits(int port) throws Exception {
        Check.that("cabecera enorme da 431",
                Fixture.raw(port, "GET / HTTP/1.1\r\nHost: x\r\nX-Grande: " + "x".repeat(40_000) + "\r\n\r\n")
                        .startsWith("HTTP/1.1 431"));
        Check.that("cuerpo mayor que el límite da 413",
                Fixture.raw(port, "POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: 999999\r\n"
                        + "Connection: close\r\n\r\n").startsWith("HTTP/1.1 413"));
        Check.that("demasiadas cabeceras da 431",
                Fixture.raw(port, "GET / HTTP/1.1\r\nHost: x\r\n" + "X-A: 1\r\n".repeat(200) + "\r\n")
                        .startsWith("HTTP/1.1 431"));
    }
}
