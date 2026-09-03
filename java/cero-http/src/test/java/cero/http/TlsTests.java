package cero.http;

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

        SSLContext context = Tls.fromKeystore(Fixture.keystore(), "cerotest".toCharArray());

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

        recargaDeCertificado();
    }

    /**
     * Renovar un certificado no debería obligar a reiniciar. Se arranca con uno, se sustituye el
     * archivo por otro con un sujeto distinto, se recarga y se comprueba que las conexiones
     * nuevas presentan ya el nuevo.
     */
    private static void recargaDeCertificado() throws Exception {
        java.nio.file.Path keystore = java.nio.file.Path.of("target", "test-recarga.p12");
        java.nio.file.Files.deleteIfExists(keystore);
        generar(keystore, "CN=primero.local");

        Tls.Certificado certificado = Tls.reloadable(keystore, "cerotest".toCharArray());
        ServerOptions options = ServerOptions.builder()
                .port(0).host("127.0.0.1").tls(certificado.context()).build();

        try (Server server = Server.start(options, (req, res) -> res.text("ok"), ErrorReporter.silent())) {
            Check.equal("arranca con el primer certificado",
                    sujetoPresentado(server.port()), "CN=primero.local");

            java.nio.file.Files.deleteIfExists(keystore);
            generar(keystore, "CN=segundo.local");
            Check.equal("antes de recargar sigue el primero",
                    sujetoPresentado(server.port()), "CN=primero.local");

            certificado.reload();
            Check.equal("después de recargar presenta el nuevo, sin reiniciar",
                    sujetoPresentado(server.port()), "CN=segundo.local");
            Check.equal("y el servidor sigue atendiendo", cuerpo(server.port()), "ok");
        }

        java.nio.file.Files.deleteIfExists(keystore);
    }

    private static String sujetoPresentado(int puerto) throws Exception {
        HttpResponse<String> respuesta = Fixture.trustingClient().send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + puerto + "/"))
                        .version(HttpClient.Version.HTTP_1_1).build(),
                HttpResponse.BodyHandlers.ofString());
        java.security.cert.X509Certificate presentado =
                (java.security.cert.X509Certificate) respuesta.sslSession()
                        .orElseThrow().getPeerCertificates()[0];
        return presentado.getSubjectX500Principal().getName();
    }

    private static String cuerpo(int puerto) throws Exception {
        return Fixture.trustingClient().send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + puerto + "/"))
                        .version(HttpClient.Version.HTTP_1_1).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void generar(java.nio.file.Path destino, String dn) throws Exception {
        Process keytool = new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "lux", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-storetype", "PKCS12",
                "-keystore", destino.toString(), "-storepass", "cerotest", "-keypass", "cerotest",
                "-dname", dn, "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true).start();
        if (!keytool.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || keytool.exitValue() != 0) {
            throw new IllegalStateException("keytool falló generando " + dn);
        }
    }
}
