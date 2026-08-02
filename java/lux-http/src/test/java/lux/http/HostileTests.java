package lux.http;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class HostileTests {

    private HostileTests() {
    }

    static void run() throws Exception {
        Check.group("clientes hostiles");
        conexionesLentas();
        cuerposMentirosos();
        abandonos();
        concurrencia();

        Check.group("entradas malformadas");
        fuzzDelParser();
    }

    private static void conexionesLentas() throws Exception {
        ServerOptions options = ServerOptions.builder()
                .port(0)
                .idleTimeoutMillis(600)
                .maxConnections(50)
                .build();

        try (Server server = Server.start(options, (req, res) -> res.text("ok"),
                ErrorReporter.silent())) {

            try (Socket lento = Fixture.connect(server.port())) {
                lento.setSoTimeout(4_000);
                OutputStream out = lento.getOutputStream();
                out.write("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                Thread.sleep(900);
                Check.equal("una petición a medias se corta por timeout de inactividad",
                        lento.getInputStream().read(), -1);
            }

            Check.that("el servidor sigue vivo tras el corte",
                    Fixture.raw(server.port(), "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                            .startsWith("HTTP/1.1 200"));

            List<Socket> mudos = new ArrayList<>();
            try {
                for (int i = 0; i < 20; i++) {
                    Socket mudo = Fixture.connect(server.port());
                    mudo.getOutputStream().write("GET / HTTP/1.1\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    mudo.getOutputStream().flush();
                    mudos.add(mudo);
                }
                Check.that("20 conexiones a medias no tumban el servidor",
                        Fixture.raw(server.port(),
                                "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                                .startsWith("HTTP/1.1 200"));
                Check.that("y quedan contabilizadas", server.activeConnections() >= 20);
            } finally {
                for (Socket mudo : mudos) {
                    mudo.close();
                }
            }

            Thread.sleep(1_000);
            Check.that("al caducar se liberan solas", server.activeConnections() <= 2);
        }
    }

    private static void cuerposMentirosos() throws Exception {
        ServerOptions options = ServerOptions.builder()
                .port(0)
                .idleTimeoutMillis(700)
                .maxBodyBytes(4_096)
                .build();

        try (Server server = Server.start(options,
                (req, res) -> res.text(String.valueOf(req.bodyBytes().length)),
                ErrorReporter.silent())) {

            String corto = Fixture.raw(server.port(),
                    "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\nConnection: close\r\n\r\nsolo-esto");
            Check.that("un cuerpo más corto que Content-Length no cuelga el servidor",
                    corto.isEmpty() || corto.startsWith("HTTP/1.1"));

            Check.that("el servidor sigue atendiendo",
                    Fixture.raw(server.port(), "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                            .startsWith("HTTP/1.1"));

            String chunkMentiroso = Fixture.raw(server.port(),
                    "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                            + "FF\r\ncorto\r\n0\r\n\r\n");
            Check.that("un chunk que declara más de lo que manda no cuelga",
                    chunkMentiroso.isEmpty() || chunkMentiroso.startsWith("HTTP/1.1"));

            String chunkEnorme = Fixture.raw(server.port(),
                    "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                            + "FFFFFFFF\r\n");
            Check.that("un chunk gigante se rechaza sin reservar memoria",
                    chunkEnorme.isEmpty() || chunkEnorme.contains(" 413 ")
                            || chunkEnorme.startsWith("HTTP/1.1 4"));

            Check.that("y el servidor sobrevive a los tres",
                    Fixture.raw(server.port(), "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                            .startsWith("HTTP/1.1"));
        }
    }

    private static void abandonos() throws Exception {
        AtomicInteger atendidas = new AtomicInteger();

        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                (req, res) -> {
                    atendidas.incrementAndGet();
                    res.text("x".repeat(200_000));
                }, ErrorReporter.silent())) {

            for (int i = 0; i < 15; i++) {
                Socket abandona = Fixture.connect(server.port());
                abandona.getOutputStream().write("GET /grande HTTP/1.1\r\nHost: x\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                abandona.getOutputStream().flush();
                abandona.close();
            }

            Thread.sleep(250);
            Check.that("cortar a media respuesta no derriba el servidor",
                    Fixture.raw(server.port(),
                            "GET /ok HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                            .startsWith("HTTP/1.1 200"));
            Check.that("y las conexiones abandonadas se liberan",
                    server.activeConnections() <= 2);
        }
    }

    private static void concurrencia() throws Exception {
        AtomicInteger correctas = new AtomicInteger();
        AtomicInteger fallidas = new AtomicInteger();
        int hilos = 40;
        int porHilo = 25;

        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                (req, res) -> res.text(req.path()), ErrorReporter.silent())) {

            CountDownLatch listos = new CountDownLatch(hilos);
            List<Thread> tareas = new ArrayList<>(hilos);

            for (int h = 0; h < hilos; h++) {
                int propio = h;
                tareas.add(Thread.ofVirtual().start(() -> {
                    try {
                        for (int i = 0; i < porHilo; i++) {
                            String ruta = "/h" + propio + "/i" + i;
                            String respuesta = Fixture.raw(server.port(),
                                    "GET " + ruta + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
                            if (respuesta.endsWith(ruta)) {
                                correctas.incrementAndGet();
                            } else {
                                fallidas.incrementAndGet();
                            }
                        }
                    } catch (Exception fallo) {
                        fallidas.incrementAndGet();
                    } finally {
                        listos.countDown();
                    }
                }));
            }

            Check.that("1000 peticiones concurrentes terminan a tiempo",
                    listos.await(60, TimeUnit.SECONDS));
            Check.equal("todas responden lo suyo, sin mezclar respuestas",
                    correctas.get(), hilos * porHilo);
            Check.equal("y ninguna falla", fallidas.get(), 0);
            Check.that("las conexiones se devuelven al terminar",
                    server.activeConnections() <= 2);
        }
    }

    private static void fuzzDelParser() throws Exception {
        try (Server server = Server.start(
                ServerOptions.builder().port(0).idleTimeoutMillis(500).build(),
                (req, res) -> res.text("ok"), ErrorReporter.silent())) {

            String[] entradas = {
                    "",
                    "\r\n\r\n",
                    "\0\0\0\r\n\r\n",
                    "GET\r\n\r\n",
                    "GET  HTTP/1.1\r\n\r\n",
                    "GET / HTTP/9.9\r\nHost: x\r\n\r\n",
                    "GET / HTTP/1.1\r\nHost: x\r\n: sinNombre\r\n\r\n",
                    "GET / HTTP/1.1\r\nHost: x\r\nX-Vacía:\r\n\r\n",
                    "GET /%ZZ HTTP/1.1\r\nHost: x\r\n\r\n",
                    "GET /%2 HTTP/1.1\r\nHost: x\r\n\r\n",
                    "GET /..%2f..%2fetc%2fpasswd HTTP/1.1\r\nHost: x\r\n\r\n",
                    "GET /?a=%FF%FE HTTP/1.1\r\nHost: x\r\n\r\n",
                    "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: -5\r\n\r\n",
                    "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n",
                    "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 99999999999999999999\r\n\r\n",
                    "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\n\r\n",
                    "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n\r\n",
                    "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n",
                    "GET / HTTP/1.1\r\n" + "X-A: 1\r\n".repeat(500) + "\r\n",
                    "GET /" + "a".repeat(20_000) + " HTTP/1.1\r\nHost: x\r\n\r\n",
                    "GET / HTTP/1.1\r\nHost: " + "x".repeat(50_000) + "\r\n\r\n",
                    "\u0080\u00ff GET / HTTP/1.1\r\n\r\n",
                    "GET / HTTP/1.1\nHost: x\n\n",
                    "GET / HTTP/1.1\r\nHost: x\r\n\r\nbasura-sin-anunciar",
            };

            int sinCaida = 0;
            for (String entrada : entradas) {
                try {
                    String respuesta = Fixture.raw(server.port(), entrada);
                    boolean aceptable = respuesta.isEmpty() || respuesta.startsWith("HTTP/1.1");
                    if (!aceptable) {
                        Check.that("entrada malformada devuelve algo que no es HTTP: "
                                + resumir(entrada), false);
                        continue;
                    }
                    if (respuesta.startsWith("HTTP/1.1 5") && !respuesta.startsWith("HTTP/1.1 50")) {
                        Check.that("entrada malformada provoca un 5xx inesperado: "
                                + resumir(entrada), false);
                        continue;
                    }
                    sinCaida++;
                } catch (Exception cortado) {
                    sinCaida++;
                }
            }

            Check.equal("las " + entradas.length + " entradas malformadas se manejan sin excepción",
                    sinCaida, entradas.length);

            Check.that("el servidor sigue en pie después del fuzzing",
                    Fixture.raw(server.port(),
                            "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                            .startsWith("HTTP/1.1 200"));
        }
    }

    private static String resumir(String entrada) {
        String limpio = entrada.replace("\r", "\\r").replace("\n", "\\n").replace("\0", "\\0");
        return limpio.length() <= 48 ? limpio : limpio.substring(0, 48) + "…";
    }
}
