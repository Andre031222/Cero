package cero.core;

import cero.http.ErrorReporter;
import cero.http.Server;

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
        Server servidor = Cero.app().port(0).quiet().reporter(ErrorReporter.silent())
                .views(vista())
                .live(live)
                .start();
        String base = "http://127.0.0.1:" + servidor.port();

        try {
            // El cliente lo sirve el propio framework: sin paso de compilación y sin
            // posibilidad de desplegar el framework sin él.
            var guion = Cliente.get(base + "/cero/live.js");
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
                    .buildAsync(URI.create("ws://127.0.0.1:" + servidor.port() + "/cero/live"), oyente)
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
            Cero.app().port(0).quiet().live(Live.enabled());
            Check.that("live() sin vistas debería haber fallado", false);
        } catch (IllegalStateException esperado) {
            Check.that("live() sin vistas falla al configurar, no al empujar",
                    esperado.getMessage().contains("motor de vistas"));
        }

        autorizacion();
        topes();
        limpieza();
        origen();
    }

    /** Abre un socket contra /cero/live, opcionalmente declarando un Origin. */
    private static WebSocket abrir(Server servidor, Oyente oyente, String origen) throws Exception {
        var constructor = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5));
        if (origen != null) {
            constructor = constructor.header("Origin", origen);
        }
        WebSocket socket = constructor
                .buildAsync(URI.create("ws://127.0.0.1:" + servidor.port() + "/cero/live"), oyente)
                .get(5, TimeUnit.SECONDS);
        oyente.abierto.get(5, TimeUnit.SECONDS);
        return socket;
    }

    private static Server arrancar(Live live) {
        return Cero.app().port(0).quiet().reporter(ErrorReporter.silent())
                .views(vista()).live(live).start();
    }

    /** Una zona con datos de una persona no puede oírla cualquiera que escriba su nombre. */
    private static void autorizacion() throws Exception {
        Check.group("live · autorización de zonas");

        Live live = Live.enabled().autorizar((peticion, zona) ->
                !zona.startsWith("privada") || "si".equals(peticion.header("X-Puede")));
        Server servidor = arrancar(live);
        try {
            Oyente sin = new Oyente();
            WebSocket socketSin = abrir(servidor, sin, null);
            try {
                socketSin.sendText("publica,privada-42", true).get(5, TimeUnit.SECONDS);
                esperar(() -> live.listeners("publica") == 1);

                Check.equal("la zona pública se acepta", live.listeners("publica"), 1);
                Check.equal("la privada no", live.listeners("privada-42"), 0);
                Check.equal("y la zona rechazada ni siquiera existe en el mapa", live.zonas(), 1);

                live.push("privada-42", "x.html", "secreto");
                Thread.sleep(120);
                Check.equal("no le llega nada de la zona que no le tocaba", sin.recibidos.size(), 0);

                // Rechazar una zona no cierra el socket: las demás siguen funcionando.
                live.push("publica", "p.html", "hola");
                esperar(() -> !sin.recibidos.isEmpty());
                Check.equal("y sí lo de la zona que sí", sin.recibidos.size(), 1);
            } finally {
                socketSin.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
            }

            Oyente con = new Oyente();
            WebSocket socketCon = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).header("X-Puede", "si")
                    .buildAsync(URI.create("ws://127.0.0.1:" + servidor.port() + "/cero/live"), con)
                    .get(5, TimeUnit.SECONDS);
            try {
                con.abierto.get(5, TimeUnit.SECONDS);
                socketCon.sendText("privada-42", true).get(5, TimeUnit.SECONDS);
                esperar(() -> live.listeners("privada-42") == 1);
                Check.equal("quien sí puede, se suscribe", live.listeners("privada-42"), 1);

                live.push("privada-42", "x.html", "secreto");
                esperar(() -> !con.recibidos.isEmpty());
                Check.that("y recibe lo suyo", con.recibidos.get(0).contains("secreto"));
            } finally {
                socketCon.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
            }
        } finally {
            servidor.stop();
        }
    }

    /** Un cliente no puede crear entradas de mapa a voluntad. */
    private static void topes() throws Exception {
        Check.group("live · topes");

        Live live = Live.enabled();
        Server servidor = arrancar(live);
        try {
            Oyente oyente = new Oyente();
            WebSocket socket = abrir(servidor, oyente, null);
            try {
                StringBuilder muchas = new StringBuilder();
                for (int i = 0; i < 5_000; i++) {
                    muchas.append("z").append(i).append(',');
                }
                socket.sendText(muchas.toString(), true).get(5, TimeUnit.SECONDS);
                esperar(() -> live.zonas() >= 32);
                Thread.sleep(150);

                Check.that("cinco mil nombres no crean cinco mil zonas", live.zonas() <= 32);

                socket.sendText("otra1,otra2,otra3", true).get(5, TimeUnit.SECONDS);
                Thread.sleep(150);
                Check.that("y volver a pedir tampoco sube el tope", live.zonas() <= 32);

                socket.sendText("z0", true).get(5, TimeUnit.SECONDS);
                Thread.sleep(100);
                Check.equal("repetir una que ya tiene no gasta cupo", live.listeners("z0"), 1);

                String larga = "x".repeat(200);
                socket.sendText(larga, true).get(5, TimeUnit.SECONDS);
                Thread.sleep(100);
                Check.equal("un nombre larguísimo se ignora", live.listeners(larga), 0);
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
            }
            esperar(() -> live.zonas() == 0);
            Check.equal("al irse, no queda ninguna zona", live.zonas(), 0);
        } finally {
            servidor.stop();
        }
    }

    /** El mapa de zonas tiene que volver a cero, no quedarse con conjuntos vacíos. */
    private static void limpieza() throws Exception {
        Check.group("live · el mapa vuelve a cero");

        Live live = Live.enabled();
        Server servidor = arrancar(live);
        try {
            for (int vuelta = 0; vuelta < 3; vuelta++) {
                Oyente oyente = new Oyente();
                WebSocket socket = abrir(servidor, oyente, null);
                socket.sendText("a,b,c", true).get(5, TimeUnit.SECONDS);
                esperar(() -> live.zonas() == 3);
                Check.equal("con un oyente hay tres zonas", live.zonas(), 3);
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
                esperar(() -> live.zonas() == 0);
                Check.equal("y al cerrarse no queda ninguna", live.zonas(), 0);
            }

            // Dos a la vez: la zona sobrevive mientras quede alguien.
            Oyente uno = new Oyente();
            Oyente dos = new Oyente();
            WebSocket s1 = abrir(servidor, uno, null);
            WebSocket s2 = abrir(servidor, dos, null);
            s1.sendText("compartida", true).get(5, TimeUnit.SECONDS);
            s2.sendText("compartida", true).get(5, TimeUnit.SECONDS);
            esperar(() -> live.listeners("compartida") == 2);
            Check.equal("dos oyentes en la misma zona", live.listeners("compartida"), 2);

            s1.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
            esperar(() -> live.listeners("compartida") == 1);
            Check.equal("se va uno y la zona sigue", live.listeners("compartida"), 1);
            Check.equal("y sigue en el mapa", live.zonas(), 1);

            s2.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
            esperar(() -> live.zonas() == 0);
            Check.equal("se va el último y la zona desaparece", live.zonas(), 0);
        } finally {
            servidor.stop();
        }
    }

    /** Otra web no puede abrir el canal con la sesión del visitante. */
    private static void origen() throws Exception {
        Check.group("live · origen");

        Live live = Live.enabled();
        Server servidor = arrancar(live);
        String propio = "http://127.0.0.1:" + servidor.port();
        try {
            try {
                abrir(servidor, new Oyente(), "https://mala.pe");
                Check.that("un origen ajeno debería haber sido rechazado", false);
            } catch (Exception rechazado) {
                Check.that("un origen ajeno se rechaza", true);
            }
            Check.equal("y no deja ninguna zona suscrita", live.zonas(), 0);
            esperar(() -> servidor.activeConnections() == 0);
            Check.equal("ni conexiones vivas", servidor.activeConnections(), 0);

            WebSocket socket = abrir(servidor, new Oyente(), propio);
            try {
                socket.sendText("z", true).get(5, TimeUnit.SECONDS);
                esperar(() -> live.listeners("z") == 1);
                Check.equal("el mismo origen sí entra", live.listeners("z"), 1);
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
            }

            WebSocket cliente = abrir(servidor, new Oyente(), null);
            try {
                Check.that("un cliente que no es navegador, sin Origin, entra", cliente != null);
            } finally {
                cliente.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
            }
        } finally {
            servidor.stop();
        }

        Server otro = arrancar(Live.enabled().origenes("https://panel.ginit.dev"));
        try {
            WebSocket socket = abrir(otro, new Oyente(), "https://panel.ginit.dev");
            try {
                Check.that("un origen declarado entra", socket != null);
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
            }
        } finally {
            otro.stop();
        }
    }
}
