package lux.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class HttpTests {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        ServerOptions options = ServerOptions.defaults()
                .port(0)
                .maxBodyBytes(4_096)
                .idleTimeoutMillis(2_000);

        try (Server server = Server.start(options, HttpTests::route, ErrorReporter.silent())) {
            int port = server.port();
            String base = "http://127.0.0.1:" + port;

            requestLine(base);
            queryParameters(base);
            bodyWithContentLength(base);
            bodyChunked(base);
            headResponse(base);
            streamedResponse(base);
            redirectResponse(base);
            handlerErrors(base);
            headerInjection(base);
            percentDecodedPath(base);
            keepAliveReuse(port);
            connectionClose(port);
            malformedRequestLine(port);
            oversizedHeader(port);
            oversizedBody(base);
            unsupportedVersion(port);
        }

        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("  TOTAL  pass=%d  fail=%d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
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
            case "/inyeccion" -> {
                response.header("X-Eco", "valor\r\nX-Colado: si");
                response.text("no debería llegar");
            }
            default -> response.status(404).text("404");
        }
    }

    private static void requestLine(String base) throws Exception {
        HttpResponse<String> response = get(base + "/");
        check("GET devuelve 200", response.statusCode() == 200);
        check("GET devuelve el cuerpo", response.body().equals("lux"));
        check("Content-Length presente", response.headers().firstValue("content-length").orElse("").equals("3"));
        check("Date presente", response.headers().firstValue("date").isPresent());
    }

    private static void queryParameters(String base) throws Exception {
        HttpResponse<String> response = get(base + "/consulta?a=uno&b=dos%20tres");
        check("query decodifica valores", response.body().equals("uno|dos tres"));
    }

    private static void bodyWithContentLength(String base) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/eco"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .POST(HttpRequest.BodyPublishers.ofString("hola mundo"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        check("POST con Content-Length", response.body().equals("hola mundo"));
    }

    private static void bodyChunked(String base) throws Exception {
        String response = raw("127.0.0.1", portOf(base),
                "POST /eco HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "4\r\nhola\r\n2\r\n s\r\n0\r\n\r\n");
        check("POST chunked", response.endsWith("hola s"));
    }

    private static void headResponse(String base) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        check("HEAD sin cuerpo", response.body().isEmpty());
        check("HEAD conserva Content-Length",
                response.headers().firstValue("content-length").orElse("").equals("3"));
    }

    private static void streamedResponse(String base) throws Exception {
        HttpResponse<String> response = get(base + "/flujo");
        check("stream() responde 200", response.statusCode() == 200);
        check("stream() concatena chunks", response.body().equals("unodos"));
        check("stream() usa chunked",
                response.headers().firstValue("transfer-encoding").orElse("").equals("chunked"));
    }

    private static void redirectResponse(String base) throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(HttpRequest.newBuilder(URI.create(base + "/redir"))
                                .version(HttpClient.Version.HTTP_1_1).build(),
                        HttpResponse.BodyHandlers.ofString());
        check("redirect devuelve 302", response.statusCode() == 302);
        check("redirect fija Location",
                response.headers().firstValue("location").orElse("").equals("/destino"));
    }

    private static void handlerErrors(String base) throws Exception {
        check("HttpException fija el estado", get(base + "/no-existe").statusCode() == 404);
        check("excepción no controlada da 500", get(base + "/revienta").statusCode() == 500);
        check("ruta desconocida da 404", get(base + "/ninguna").statusCode() == 404);
    }

    private static void headerInjection(String base) throws Exception {
        String response = raw("127.0.0.1", portOf(base),
                "GET /inyeccion HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        check("CRLF en cabecera no se cuela", !response.contains("X-Colado"));
        check("CRLF en cabecera da 500", response.startsWith("HTTP/1.1 500"));
    }

    private static void percentDecodedPath(String base) throws Exception {
        HttpResponse<String> response = get(base + "/ruta%20con%20espacio");
        check("el path se decodifica", response.body().equals("decodificado"));
    }

    private static void keepAliveReuse(int port) throws Exception {
        try (Socket socket = connect(port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("GET /metodo HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String first = readHead(in);
            check("keep-alive: primera respuesta", first.contains("200 OK"));
            check("keep-alive: anunciado", first.toLowerCase().contains("connection: keep-alive"));
            readExactly(in, contentLength(first));

            out.write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String second = readHead(in);
            check("keep-alive: segunda respuesta en la misma conexión", second.contains("200 OK"));
            check("keep-alive: cuerpo correcto", readExactly(in, contentLength(second)).equals("lux"));
        }
    }

    private static void connectionClose(int port) throws Exception {
        String response = raw("127.0.0.1", port, "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        check("Connection: close se respeta", response.toLowerCase().contains("connection: close"));
        check("Connection: close cierra el socket", response.endsWith("lux"));
    }

    private static void malformedRequestLine(int port) throws Exception {
        String response = raw("127.0.0.1", port, "ESTO NO ES HTTP\r\n\r\n");
        check("línea de petición inválida da 4xx o 5xx", response.startsWith("HTTP/1.1 5")
                || response.startsWith("HTTP/1.1 4"));
    }

    private static void oversizedHeader(int port) throws Exception {
        String relleno = "x".repeat(40_000);
        String response = raw("127.0.0.1", port,
                "GET / HTTP/1.1\r\nHost: x\r\nX-Grande: " + relleno + "\r\n\r\n");
        check("cabecera enorme da 431", response.startsWith("HTTP/1.1 431"));
    }

    private static void oversizedBody(String base) throws Exception {
        String response = raw("127.0.0.1", portOf(base),
                "POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: 999999\r\nConnection: close\r\n\r\n");
        check("cuerpo mayor que el límite da 413", response.startsWith("HTTP/1.1 413"));
    }

    private static void unsupportedVersion(int port) throws Exception {
        String response = raw("127.0.0.1", port, "GET / HTTP/2.0\r\nHost: x\r\n\r\n");
        check("versión no soportada da 505", response.startsWith("HTTP/1.1 505"));
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create(url)).version(HttpClient.Version.HTTP_1_1).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String raw(String host, int port, String request) throws IOException {
        try (Socket socket = connect(port)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int state = 0;
        int value;
        while (state < 4 && (value = in.read()) >= 0) {
            head.append((char) value);
            boolean expectedCr = state == 0 || state == 2;
            if (value == (expectedCr ? '\r' : '\n')) {
                state++;
            } else {
                state = value == '\r' ? 1 : 0;
            }
        }
        return head.toString();
    }

    private static String readExactly(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int step = in.read(body, read, length - read);
            if (step < 0) {
                break;
            }
            read += step;
        }
        return new String(body, 0, read, StandardCharsets.UTF_8);
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                return Integer.parseInt(line.substring(15).trim());
            }
        }
        return 0;
    }

    private static int portOf(String base) {
        return Integer.parseInt(base.substring(base.lastIndexOf(':') + 1));
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
