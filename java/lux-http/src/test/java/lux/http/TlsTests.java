package lux.http;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class TlsTests {

    private TlsTests() {
    }

    static void run() throws Exception {
        Check.group("TLS");

        SSLContext context = Tls.fromKeystore(Fixture.keystore(), "luxtest".toCharArray());

        ServerOptions options = ServerOptions.builder()
                .port(0)
                .host("127.0.0.1")
                .tls(context)
                .build();

        try (Server server = Server.start(options, (req, res) -> {
            res.header("X-Secure", String.valueOf(req.secure()));
            res.text("cifrado");
        }, ErrorReporter.silent())) {

            Check.that("el servidor se declara seguro", server.secure());

            HttpClient client = Fixture.trustingClient();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/"))
                            .version(HttpClient.Version.HTTP_1_1).build(),
                    HttpResponse.BodyHandlers.ofString());

            Check.equal("HTTPS responde 200", response.statusCode(), 200);
            Check.equal("HTTPS devuelve el cuerpo", response.body(), "cifrado");
            Check.equal("request.secure() es true",
                    response.headers().firstValue("x-secure").orElse(null), "true");
            Check.that("la conexión negoció TLS",
                    response.sslSession().isPresent()
                            && response.sslSession().get().getProtocol().startsWith("TLS"));

            String plano;
            try {
                plano = Fixture.raw(server.port(), "GET / HTTP/1.1\r\nHost: x\r\n\r\n");
            } catch (Exception rechazado) {
                plano = "";
            }
            Check.that("texto plano contra puerto TLS no obtiene respuesta HTTP",
                    !plano.startsWith("HTTP/"));
            Check.that("texto plano contra puerto TLS no filtra el cuerpo", !plano.contains("cifrado"));
        }
    }
}
