package cero.http;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Eventos del servidor al navegador. */
final class SseTests {

    private SseTests() {
    }

    static void run() throws Exception {
        Check.group("eventos del servidor");

        cabeceras();
        lleganUnoAUno();
        seEnteraDeQueElClienteSeFue();
    }

    private static void cabeceras() throws Exception {
        Handler handler = (req, res) -> {
            try (Sse eventos = Sse.open(res)) {
                eventos.send("hola");
            }
        };
        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                handler, ErrorReporter.silent())) {
            String crudo = Fixture.raw(server.port(),
                    "GET /e HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            String bajo = crudo.toLowerCase();

            Check.that("el tipo es text/event-stream", bajo.contains("content-type: text/event-stream"));
            Check.that("sin caché", bajo.contains("cache-control: no-cache"));
            Check.that("y le dice a nginx que no acumule", bajo.contains("x-accel-buffering: no"));
            Check.that("la trama va con data:", crudo.contains("data: hola"));
        }
    }

    /**
     * Lo único que de verdad importa: que cada evento salga en cuanto se manda. Si el servidor
     * los acumula y los suelta al cerrar, esto deja de ser tiempo real y no sirve para nada.
     */
    private static void lleganUnoAUno() throws Exception {
        CountDownLatch primero = new CountDownLatch(1);
        CountDownLatch seguir = new CountDownLatch(1);

        Handler handler = (req, res) -> {
            try (Sse eventos = Sse.open(res)) {
                eventos.send("uno");
                primero.countDown();
                seguir.await(10, TimeUnit.SECONDS);   // no se manda el segundo hasta que se pida
                eventos.send("dos");
            }
        };

        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                handler, ErrorReporter.silent());
             Socket socket = Fixture.connect(server.port())) {

            socket.getOutputStream().write(
                    "GET /e HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            BufferedReader lector = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            Check.that("el servidor manda el primero sin esperar a terminar",
                    primero.await(10, TimeUnit.SECONDS));

            List<String> recibidas = new ArrayList<>();
            String linea;
            while ((linea = lector.readLine()) != null) {
                recibidas.add(linea);
                if (linea.equals("data: uno")) {
                    break;
                }
            }
            Check.that("y llega ANTES de que se mande el segundo", recibidas.contains("data: uno"));

            seguir.countDown();

            boolean segundo = false;
            while ((linea = lector.readLine()) != null) {
                if (linea.equals("data: dos")) {
                    segundo = true;
                    break;
                }
            }
            Check.that("el segundo llega cuando toca", segundo);
        }
    }

    /** Si el navegador cierra la pestaña, el bucle del servidor tiene que enterarse y salir. */
    private static void seEnteraDeQueElClienteSeFue() throws Exception {
        CountDownLatch salio = new CountDownLatch(1);

        Handler handler = (req, res) -> {
            try (Sse eventos = Sse.open(res)) {
                for (int i = 0; i < 2_000 && eventos.abierto(); i++) {
                    eventos.send("tic " + i);
                    Thread.sleep(5);
                }
                salio.countDown();
            }
        };

        try (Server server = Server.start(ServerOptions.builder().port(0).build(),
                handler, ErrorReporter.silent())) {
            Socket socket = Fixture.connect(server.port());
            socket.getOutputStream().write(
                    "GET /e HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            socket.getInputStream().read();
            socket.close();

            Check.that("el bucle termina solo cuando el cliente se va",
                    salio.await(20, TimeUnit.SECONDS));
        }
    }
}
