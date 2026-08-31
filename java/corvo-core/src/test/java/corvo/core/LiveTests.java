package corvo.core;

import corvo.http.ErrorReporter;
import corvo.http.Server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

final class LiveTests {

    private LiveTests() {
    }

    /** Motor de vistas mínimo: devuelve la plantilla con el modelo pegado detrás. */
    private static ViewRenderer vista() {
        return (plantilla, modelo) -> "<b>" + plantilla + ":" + modelo + "</b>";
    }

    /** Recoge los mensajes que llegan por el socket. */
    private static final class Oyente implements WebSocket.Listener {
        final List<String> recibidos = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> abierto = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(Long.MAX_VALUE);
            abierto.complete(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence datos, boolean ultimo) {
            recibidos.add(datos.toString());
            return null;
        }
    }

    private static void esperar(java.util.function.BooleanSupplier condicion) throws Exception {
        long limite = System.currentTimeMillis() + 3_000;
        while (!condicion.getAsBoolean() && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
        }
    }

    static void run() throws Exception {
        Check.group("live");

        Live live = Live.enabled();
        Server servidor = Corvo.app().port(0).quiet().reporter(ErrorReporter.silent())
                .views(vista())
                .live(live)
                .start();
        String base = "http://127.0.0.1:" + servidor.port();

        try {
            // El cliente lo sirve el propio framework: sin paso de compilación y sin
            // posibilidad de desplegar el framework sin él.
            var guion = Cliente.get(base + "/corvo/live.js");
            Check.equal("el cliente se sirve desde el framework", guion.statusCode(), 200);
            Check.that("y es JavaScript",
                    guion.headers().firstValue("Content-Type").orElse("").contains("javascript"));
            Check.that("que reconecta solo", guion.body().contains("onclose"));
            Check.that("con espera creciente", guion.body().contains("espera * 2"));

            Check.equal("sin nadie mirando, la zona no tiene oyentes", live.listeners("carrito"), 0);

            // Empujar sin oyentes no debe pintar ni fallar.
            live.push("carrito", "carrito.html", Map.of("x", 1));
            Check.equal("empujar sin oyentes no rompe nada", live.listeners("carrito"), 0);

            Oyente oyente = new Oyente();
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create("ws://127.0.0.1:" + servidor.port() + "/corvo/live"), oyente)
                    .get(5, TimeUnit.SECONDS);
            try {
                oyente.abierto.get(5, TimeUnit.SECONDS);
                socket.sendText("carrito,avisos", true).get(5, TimeUnit.SECONDS);
                esperar(() -> live.listeners("carrito") == 1);

                Check.equal("al suscribirse, la zona tiene un oyente", live.listeners("carrito"), 1);
                Check.equal("y las demás zonas del mismo socket también",
                        live.listeners("avisos"), 1);

                live.push("carrito", "carrito.html", "3 artículos");
                esperar(() -> !oyente.recibidos.isEmpty());

                Check.equal("llega un mensaje", oyente.recibidos.size(), 1);
                String marco = oyente.recibidos.get(0);
                Check.that("que dice a qué zona va", marco.contains("\"zona\":\"carrito\""));
                // Lo que viaja es HTML ya pintado: el navegador no tiene que saber pintar nada,
                // así que no hay una segunda copia de las plantillas en JavaScript.
                Check.that("y trae el HTML ya pintado en el servidor",
                        marco.contains("carrito.html:3 art"));

                live.pushHtml("avisos", "<i>hola</i>");
                esperar(() -> oyente.recibidos.size() == 2);
                Check.that("pushHtml manda HTML tal cual",
                        oyente.recibidos.get(1).contains("<i>hola</i>"));

                // Empujar a una zona que este navegador no tiene no le llega.
                live.push("inexistente", "x.html", Map.of());
                Thread.sleep(120);
                Check.equal("una zona sin oyentes no manda nada", oyente.recibidos.size(), 2);
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
            }

            esperar(() -> live.listeners("carrito") == 0);
            Check.equal("al cerrar, el socket sale de sus zonas", live.listeners("carrito"), 0);
            Check.equal("de todas ellas", live.listeners("avisos"), 0);
        } finally {
            servidor.stop();
        }

        // live() sin vistas tiene que fallar al arrancar, no la primera vez que alguien empuje.
        try {
            Corvo.app().port(0).quiet().live(Live.enabled());
            Check.that("live() sin vistas debería haber fallado", false);
        } catch (IllegalStateException esperado) {
            Check.that("live() sin vistas falla al configurar, no al empujar",
                    esperado.getMessage().contains("motor de vistas"));
        }
    }
}
