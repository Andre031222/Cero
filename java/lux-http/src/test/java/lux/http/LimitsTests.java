package lux.http;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class LimitsTests {

    private LimitsTests() {
    }

    static void run() throws Exception {
        Check.group("límites y ciclo de vida");

        hostRequired();
        connectionCeiling();
        handlerTimeout();
        handlerTimeoutNoCortaLoRapido();
        gracefulShutdown();
        keepAliveCeiling();
    }

    /**
     * El vigilante marca un límite por petición y lo borra al terminar. Si se le olvidara
     * borrarlo, la siguiente petición de la misma conexión heredaría un plazo ya vencido y
     * moriría sin motivo. Con un plazo corto y muchas peticiones seguidas eso salta enseguida.
     */
    private static void handlerTimeoutNoCortaLoRapido() throws Exception {
        ServerOptions options = ServerOptions.builder()
                .port(0)
                .handlerTimeoutMillis(150)
                .idleTimeoutMillis(5_000)
                .build();

        try (Server server = Server.start(options, (req, res) -> res.text("ok"), ErrorReporter.silent());
             Socket socket = Fixture.connect(server.port())) {

            int atendidas = 0;
            for (int i = 0; i < 50; i++) {
                socket.getOutputStream().write(
                        "GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                String head = Fixture.readHead(socket.getInputStream());
                if (!head.startsWith("HTTP/1.1 200")) {
                    break;
                }
                Fixture.readExactly(socket.getInputStream(), Fixture.contentLength(head));
                atendidas++;
            }
            Check.equal("50 peticiones seguidas bajo un plazo corto, ninguna cortada", atendidas, 50);
        }
    }

    private static void hostRequired() throws Exception {
        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                (req, res) -> res.text("ok"), ErrorReporter.silent())) {
            int port = server.port();
            Check.that("HTTP/1.1 sin Host da 400",
                    Fixture.raw(port, "GET / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"));
            Check.that("Host duplicado da 400",
                    Fixture.raw(port, "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n")
                            .startsWith("HTTP/1.1 400"));
            Check.that("HTTP/1.0 sin Host se acepta",
                    Fixture.raw(port, "GET / HTTP/1.0\r\n\r\n").startsWith("HTTP/1.1 200"));
        }

        try (Server server = Server.start(ServerOptions.builder().port(0).requireHost(false).build(),
                (req, res) -> res.text("ok"), ErrorReporter.silent())) {
            Check.that("requireHost(false) permite omitirlo",
                    Fixture.raw(server.port(), "GET / HTTP/1.1\r\nConnection: close\r\n\r\n")
                            .startsWith("HTTP/1.1 200"));
        }
    }

    private static void connectionCeiling() throws Exception {
        ServerOptions options = ServerOptions.builder()
                .port(0)
                .maxConnections(2)
                .idleTimeoutMillis(5_000)
                .build();

        try (Server server = Server.start(options, (req, res) -> res.text("ok"), ErrorReporter.silent())) {
            List<Socket> held = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) {
                    Socket socket = Fixture.connect(server.port());
                    socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    Fixture.readHead(socket.getInputStream());
                    held.add(socket);
                }
                Check.equal("se contabilizan las conexiones activas", server.activeConnections(), 2);

                Check.that("la conexión por encima del techo se cierra", closedImmediately(server.port()));
            } finally {
                for (Socket socket : held) {
                    socket.close();
                }
            }
        }
    }

    private static boolean closedImmediately(int port) {
        try (Socket extra = Fixture.connect(port)) {
            extra.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            extra.getOutputStream().flush();
            return extra.getInputStream().read() == -1;
        } catch (java.io.IOException reset) {
            return true;
        }
    }

    private static void handlerTimeout() throws Exception {
        ServerOptions options = ServerOptions.builder()
                .port(0)
                .handlerTimeoutMillis(300)
                .idleTimeoutMillis(5_000)
                .build();

        AtomicBoolean lento = new AtomicBoolean();
        Handler handler = (req, res) -> {
            if (req.path().equals("/lento")) {
                lento.set(true);
                Thread.sleep(3_000);
            }
            res.text("ok");
        };

        try (Server server = Server.start(options, handler, ErrorReporter.silent())) {
            long start = System.nanoTime();
            String response = Fixture.raw(server.port(), "GET /lento HTTP/1.1\r\nHost: x\r\n\r\n");
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            Check.that("el handler lento se ejecutó", lento.get());
            Check.that("el timeout corta antes de que termine el handler", elapsed < 2_000);
            Check.that("el timeout deja la respuesta vacía", response.isEmpty());
            Check.that("el servidor sigue atendiendo tras el corte",
                    Fixture.raw(server.port(), "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                            .startsWith("HTTP/1.1 200"));
        }
    }

    private static void gracefulShutdown() throws Exception {
        ServerOptions options = ServerOptions.builder()
                .port(0)
                .handlerTimeoutMillis(0)
                .shutdownGraceMillis(5_000)
                .build();

        CountDownLatch entered = new CountDownLatch(1);
        Handler handler = (req, res) -> {
            entered.countDown();
            Thread.sleep(400);
            res.text("terminada");
        };

        Server server = Server.start(options, handler, ErrorReporter.silent());
        int port = server.port();

        StringBuilder captured = new StringBuilder();
        Thread client = Thread.ofVirtual().start(() -> {
            try {
                captured.append(Fixture.raw(port, "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"));
            } catch (Exception ignored) {
            }
        });

        Check.that("el handler arrancó", entered.await(3, TimeUnit.SECONDS));
        server.stop();
        client.join(5_000);

        Check.that("la petición en vuelo termina durante el apagado",
                captured.toString().endsWith("terminada"));
        Check.that("el servidor queda parado", !server.running());
    }

    private static void keepAliveCeiling() throws Exception {
        ServerOptions options = ServerOptions.builder()
                .port(0)
                .maxKeepAliveRequests(2)
                .build();

        try (Server server = Server.start(options, (req, res) -> res.text("ok"), ErrorReporter.silent())) {
            try (Socket socket = Fixture.connect(server.port())) {
                InputStream in = socket.getInputStream();
                String last = "";
                for (int i = 0; i < 2; i++) {
                    socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    last = Fixture.readHead(in);
                    Fixture.readExactly(in, Fixture.contentLength(last));
                }
                Check.that("la última petición permitida anuncia cierre",
                        last.toLowerCase().contains("connection: close"));
                Check.equal("y la conexión se cierra", in.read(), -1);
            }
        }
    }
}
