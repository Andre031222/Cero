package corvo.http;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class Fixture {

    private Fixture() {
    }

    static HttpResponse<String> get(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).version(HttpClient.Version.HTTP_1_1).build());
    }

    static HttpResponse<String> send(HttpRequest request) throws Exception {
        return client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<byte[]> sendBytes(HttpRequest request) throws Exception {
        return client().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    static HttpClient client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    static HttpClient trustingClient() throws Exception {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(trustEverything())
                .build();
    }

    static String raw(int port, String request) throws IOException {
        try (Socket socket = connect(port)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(5_000);
        return socket;
    }

    static String readHead(InputStream in) throws IOException {
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

    static String readExactly(InputStream in, int length) throws IOException {
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

    static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                return Integer.parseInt(line.substring(15).trim());
            }
        }
        return 0;
    }

    static String headerOf(String head, String name) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith(name.toLowerCase() + ":")) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return null;
    }

    static Path keystore() throws Exception {
        Path path = Path.of("target", "test-keystore.p12");
        if (Files.exists(path)) {
            return path;
        }
        Files.createDirectories(path.getParent());
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "lux", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-storetype", "PKCS12",
                "-keystore", path.toString(), "-storepass", "luxtest", "-keypass", "luxtest",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        if (!keytool.waitFor(30, TimeUnit.SECONDS) || keytool.exitValue() != 0) {
            throw new IllegalStateException("keytool falló: "
                    + new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        return path;
    }

    private static SSLContext trustEverything() throws Exception {
        TrustManager[] permissive = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, permissive, null);
        return context;
    }
}
