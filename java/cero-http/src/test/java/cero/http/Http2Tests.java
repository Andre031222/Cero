package cero.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP/2 contra un cliente que habla a nivel de trama.
 *
 * <p>Las pruebas se dividen en dos mitades que buscan cosas distintas. La primera comprueba que
 * un cliente correcto obtiene lo que espera —incluidos varios flujos a la vez, que es la razón de
 * ser del protocolo—. La segunda manda a propósito lo que un cliente correcto se negaría a
 * construir, y comprueba que el servidor lo rechaza con el código que manda el RFC en vez de
 * hacer algo peor: colgarse, aceptarlo o cerrar sin decir por qué.
 */
final class Http2Tests {

    private Http2Tests() {
    }

    private static Server servidor;
    private static int puerto;

    static void run() throws Exception {
        Check.group("HTTP/2 · h2c");

        ServerOptions opciones = ServerOptions.builder().port(0).idleTimeoutMillis(10_000).build();
        servidor = Server.start(opciones, Http2Tests::rutas, ErrorReporter.silent());
        puerto = servidor.port();
        try {
            loBasico();
            multiplexacion();
            cuerpos();
            ventanas();
            senales();
            streaming();
            trailers();
        } finally {
            servidor.stop();
        }

        Check.group("HTTP/2 · lo que un cliente correcto no mandaría");
        servidor = Server.start(opciones, Http2Tests::rutas, ErrorReporter.silent());
        puerto = servidor.port();
        try {
            hostiles();
        } finally {
            servidor.stop();
        }

        Check.group("HTTP/2 · las tres puertas");
        porUpgrade();
        porAlpn();
    }

    /**
     * `Upgrade: h2c` desde HTTP/1.1, que es la puerta de un cliente que no sabe de antemano si
     * el servidor habla h2. La petición que pidió el cambio no se pierde: se atiende como flujo 1.
     */
    private static void porUpgrade() throws Exception {
        ServerOptions opciones = ServerOptions.builder().port(0).build();
        try (Server s = Server.start(opciones, Http2Tests::rutas, ErrorReporter.silent())) {
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", s.port())) {
                socket.setSoTimeout(10_000);
                socket.getOutputStream().write(("GET / HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Connection: Upgrade, HTTP2-Settings\r\n"
                        + "Upgrade: h2c\r\n"
                        + "HTTP2-Settings: AAMAAABkAAQAoAAAAAIAAAAA\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                String cabecera = Fixture.readHead(socket.getInputStream());
                Check.that("el servidor acepta el cambio con un 101",
                        cabecera.startsWith("HTTP/1.1 101 Switching Protocols"));
                Check.that("y lo dice en Upgrade", cabecera.contains("Upgrade: h2c"));

                // A partir de aquí es HTTP/2: el cliente manda su preámbulo y lee el flujo 1.
                socket.getOutputStream().write(Http2.PREAMBULO);
                socket.getOutputStream().flush();
                Check.that("la petición que pidió el cambio se responde como flujo 1",
                        respuestaDelFlujoUno(socket).contains("hola por HTTP/2.0"));
            }
        }
    }

    /** Lee tramas hasta juntar el cuerpo del flujo 1. */
    private static String respuestaDelFlujoUno(java.net.Socket socket) throws IOException {
        java.io.InputStream in = socket.getInputStream();
        java.io.ByteArrayOutputStream cuerpo = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 64; i++) {
            byte[] cab = new byte[9];
            int leidos = 0;
            while (leidos < 9) {
                int n = in.read(cab, leidos, 9 - leidos);
                if (n < 0) {
                    return cuerpo.toString(StandardCharsets.UTF_8);
                }
                leidos += n;
            }
            int largo = ((cab[0] & 0xFF) << 16) | ((cab[1] & 0xFF) << 8) | (cab[2] & 0xFF);
            byte[] carga = new byte[largo];
            leidos = 0;
            while (leidos < largo) {
                leidos += in.read(carga, leidos, largo - leidos);
            }
            if ((cab[3] & 0xFF) == Http2Cliente.DATA) {
                cuerpo.write(carga);
            }
            if ((cab[3] & 0xFF) == Http2Cliente.DATA && (cab[4] & Http2Cliente.FIN_FLUJO) != 0) {
                break;
            }
        }
        return cuerpo.toString(StandardCharsets.UTF_8);
    }

    /**
     * h2 sobre TLS, negociado por ALPN. Es el único camino por el que un navegador usa HTTP/2:
     * ninguno habla h2c en claro.
     */
    private static void porAlpn() throws Exception {
        java.nio.file.Path almacen = Fixture.keystore();
        ServerOptions opciones = ServerOptions.builder().port(0)
                .tls(Tls.fromKeystore(almacen, "cerotest".toCharArray())).build();
        try (Server s = Server.start(opciones, Http2Tests::rutas, ErrorReporter.silent())) {
            javax.net.ssl.SSLContext confiado = Fixture.trustEverything();
            javax.net.ssl.SSLSocketFactory fabrica = confiado.getSocketFactory();
            try (javax.net.ssl.SSLSocket socket =
                         (javax.net.ssl.SSLSocket) fabrica.createSocket("127.0.0.1", s.port())) {
                javax.net.ssl.SSLParameters p = socket.getSSLParameters();
                p.setApplicationProtocols(new String[] { "h2" });
                socket.setSSLParameters(p);
                socket.startHandshake();

                Check.equal("el servidor acuerda h2 por ALPN", socket.getApplicationProtocol(), "h2");

                socket.getOutputStream().write(Http2.PREAMBULO);
                socket.getOutputStream().flush();
                escribirTramaCruda(socket, Http2Cliente.SETTINGS, 0, 0, new byte[0]);
                escribirTramaCruda(socket, Http2Cliente.HEADERS,
                        Http2Cliente.FIN_CABECERAS | Http2Cliente.FIN_FLUJO, 1,
                        new Hpack.Codificador().codificar(java.util.List.of(
                                new Hpack.Campo(":method", "GET"),
                                new Hpack.Campo(":scheme", "https"),
                                new Hpack.Campo(":path", "/"),
                                new Hpack.Campo(":authority", "127.0.0.1"))));
                Check.that("y responde por h2 sobre TLS",
                        respuestaDelFlujoUno(socket).contains("hola por HTTP/2.0"));
            }
        }
    }

    private static void escribirTramaCruda(java.net.Socket socket, int tipo, int banderas,
                                           int flujo, byte[] carga) throws IOException {
        socket.getOutputStream().write(new byte[] {
                (byte) (carga.length >>> 16), (byte) (carga.length >>> 8), (byte) carga.length,
                (byte) tipo, (byte) banderas,
                (byte) (flujo >>> 24), (byte) (flujo >>> 16), (byte) (flujo >>> 8), (byte) flujo });
        socket.getOutputStream().write(carga);
        socket.getOutputStream().flush();
    }

    private static void rutas(Request peticion, Response respuesta) throws Exception {
        switch (peticion.path()) {
            case "/" -> respuesta.text("hola por " + peticion.protocol());
            case "/eco" -> respuesta.send(peticion.bodyBytes());
            case "/lento" -> {
                Thread.sleep(120);
                respuesta.text("tarde: " + peticion.query("n"));
            }
            case "/grande" -> respuesta.text("x".repeat(300_000));
            case "/cabeceras" -> {
                respuesta.header("X-Una", "primera").header("X-Otra", "segunda");
                respuesta.text("con cabeceras");
            }
            case "/muchas" -> {
                // Un bloque de cabeceras que no cabe en una trama: obliga a CONTINUATION.
                for (int i = 0; i < 400; i++) {
                    respuesta.header("x-relleno-" + i, "valor bastante largo número " + i);
                }
                respuesta.text("con muchas cabeceras");
            }
            case "/vacio" -> respuesta.status(204).send(new byte[0]);
            case "/chorro" -> {
                // Escribe en trozos y va soltándolos: si stream() acumulara, esto llegaría de
                // golpe al final en vez de por partes.
                try (java.io.OutputStream salida = respuesta.stream()) {
                    for (int i = 0; i < 40; i++) {
                        salida.write(("trozo " + i + "\n").getBytes(StandardCharsets.UTF_8));
                        salida.flush();
                    }
                }
            }
            case "/chorro-grande" -> {
                try (java.io.OutputStream salida = respuesta.stream()) {
                    byte[] bloque = new byte[64 * 1024];
                    java.util.Arrays.fill(bloque, (byte) 'z');
                    for (int i = 0; i < 6; i++) {
                        salida.write(bloque);
                    }
                }
            }
            default -> {
                respuesta.status(404);
                respuesta.text("no está");
            }
        }
    }

    // ─── un cliente correcto ─────────────────────────────────────────────────────────────────

    private static void loBasico() throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.saludar();
            Http2Cliente.Trama ajustes = c.esperar(Http2Cliente.SETTINGS);
            Check.that("el servidor manda sus SETTINGS antes que nada",
                    ajustes.flujo() == 0 && (ajustes.banderas() & Http2Cliente.RECONOCE) == 0);

            Http2Cliente.Respuesta r = c.respuestaDe(c.pedir("GET", "/"));
            Check.equal("una petición devuelve 200", r.estado(), 200);
            Check.equal("y el cuerpo dice que fue por HTTP/2", r.texto(), "hola por HTTP/2.0");

            Http2Cliente.Respuesta noExiste = c.respuestaDe(c.pedir("GET", "/noexiste"));
            Check.equal("un 404 es un 404", noExiste.estado(), 404);

            Http2Cliente.Respuesta conCab = c.respuestaDe(c.pedir("GET", "/cabeceras"));
            Check.equal("las cabeceras salen en minúscula",
                    conCab.cabeceras().get("x-una"), "primera");
            Check.that("y no viajan las de conexión, que en h2 no existen",
                    !conCab.cabeceras().containsKey("connection")
                            && !conCab.cabeceras().containsKey("transfer-encoding"));

            Http2Cliente.Respuesta vacia = c.respuestaDe(c.pedir("GET", "/vacio"));
            Check.equal("un 204 llega sin cuerpo", vacia.cuerpo().length, 0);
            Check.equal("y con su código", vacia.estado(), 204);
        }
    }

    /** Varios flujos a la vez sobre una conexión. Es para lo que existe el protocolo. */
    private static void multiplexacion() throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.saludar();
            c.abrirVentanas(1 << 22);
            long comienzo = System.nanoTime();
            List<Integer> flujos = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                flujos.add(c.pedir("GET", "/lento?n=" + i));
            }
            List<String> cuerpos = new ArrayList<>();
            for (int flujo : flujos) {
                cuerpos.add(c.respuestaDe(flujo).texto());
            }
            long millis = (System.nanoTime() - comienzo) / 1_000_000;

            Check.equal("las 24 peticiones responden", cuerpos.size(), 24);
            boolean todas = true;
            for (int i = 0; i < 24; i++) {
                todas &= cuerpos.contains("tarde: " + i);
            }
            Check.that("y cada una devuelve lo suyo, sin mezclarse", todas);
            // En serie serían 24 × 120 ms ≈ 2,9 s. Si van de verdad en paralelo, un orden menos.
            Check.that("van en paralelo, no en fila (" + millis + " ms para 24 × 120 ms)",
                    millis < 1_200);
        }
    }

    private static void cuerpos() throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.saludar();
            c.abrirVentanas(1 << 22);
            byte[] pequeno = "cuerpo con eñes y acentós".getBytes(StandardCharsets.UTF_8);
            Check.equal("un cuerpo pequeño vuelve igual",
                    new String(c.respuestaDe(c.pedirCon("POST", "/eco", pequeno)).cuerpo(),
                            StandardCharsets.UTF_8),
                    "cuerpo con eñes y acentós");

            // Un cuerpo que no cabe en una trama y obliga a partirlo.
            byte[] grande = new byte[100_000];
            new java.util.Random(7).nextBytes(grande);
            byte[] vuelta = c.respuestaDe(c.pedirCon("POST", "/eco", grande)).cuerpo();
            Check.that("y uno de 100 KB, partido en tramas, también",
                    java.util.Arrays.equals(grande, vuelta));
        }
    }

    /** Más de la ventana inicial: si el control de flujo está mal, esto se para en 65 535. */
    private static void ventanas() throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.saludar();
            c.abrirVentanas(1 << 22);
            Http2Cliente.Respuesta r = c.respuestaDe(c.pedir("GET", "/grande"));
            Check.equal("300 KB llegan enteros, no se paran en la ventana inicial",
                    r.cuerpo().length, 300_000);

            Http2Cliente.Respuesta muchas = c.respuestaDe(c.pedir("GET", "/muchas"));
            Check.equal("un bloque de cabeceras que no cabe en una trama se parte y se rearma",
                    muchas.cabeceras().get("x-relleno-399"),
                    "valor bastante largo número 399");
            Check.equal("y el cuerpo llega detrás", muchas.texto(), "con muchas cabeceras");
        }
    }

    private static void senales() throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.saludar();
            byte[] marca = "12345678".getBytes(StandardCharsets.US_ASCII);
            c.trama(Http2Cliente.PING, 0, 0, marca);
            Http2Cliente.Trama pong = c.esperar(Http2Cliente.PING);
            Check.that("un PING vuelve con la misma marca y el ACK puesto",
                    (pong.banderas() & Http2Cliente.RECONOCE) != 0
                            && java.util.Arrays.equals(pong.carga(), marca));

            // Un flujo anulado a mitad no puede tumbar la conexión.
            int flujo = c.pedir("GET", "/grande");
            c.trama(Http2Cliente.RST_STREAM, 0, flujo, Http2Cliente.entero(0x8));
            c.trama(Http2Cliente.PING, 0, 0, marca);
            Check.that("tras anular un flujo, la conexión sigue viva",
                    (c.esperar(Http2Cliente.PING).banderas() & Http2Cliente.RECONOCE) != 0);
        }
    }

    /**
     * `stream()` tiene que salir en tramas según se escribe, no acumularse hasta el final.
     *
     * <p>Se comprueba de dos formas: que un chorro pequeño llega entero y en orden, y que uno de
     * 384 KB —seis veces la ventana inicial— llega también. Si se acumulara, lo segundo tendría
     * que caber en memoria antes de salir, y además el flujo se quedaría parado en 65 535.
     */
    private static void streaming() throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.saludar();
            c.abrirVentanas(1 << 22);

            Http2Cliente.Respuesta r = c.respuestaDe(c.pedir("GET", "/chorro"));
            Check.equal("un chorro responde 200", r.estado(), 200);
            StringBuilder esperado = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                esperado.append("trozo ").append(i).append('\n');
            }
            Check.equal("y llega entero y en orden", r.texto(), esperado.toString());
            Check.that("sin declarar content-length, que en streaming no se sabe",
                    !r.cabeceras().containsKey("content-length"));

            Http2Cliente.Respuesta grande = c.respuestaDe(c.pedir("GET", "/chorro-grande"));
            Check.equal("un chorro de 384 KB pasa la ventana inicial sin pararse",
                    grande.cuerpo().length, 6 * 64 * 1024);
        }
    }

    /**
     * Los trailers se descartan —como en HTTP/1.1, que también los salta— pero hay que
     * decodificarlos igual: la tabla dinámica de HPACK avanza con cada bloque, y saltarse uno
     * descoloca todos los índices siguientes de esa conexión.
     */
    private static void trailers() throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.saludar();
            c.abrirVentanas(1 << 22);

            // Una petición con cuerpo y trailers detrás.
            int flujo = c.pedirSinCerrar("POST", "/eco");
            c.trama(Http2Cliente.DATA, 0, flujo, "con trailers".getBytes(StandardCharsets.UTF_8));
            c.trama(Http2Cliente.HEADERS,
                    Http2Cliente.FIN_CABECERAS | Http2Cliente.FIN_FLUJO, flujo,
                    c.cabeceras("x-suma", "abc123"));
            Check.equal("una petición con trailers se atiende igual",
                    c.respuestaDe(flujo).texto(), "con trailers");

            // Y lo que de verdad importa: la conexión sigue entendiéndose después. Si el bloque
            // de los trailers no se hubiera decodificado, la tabla estaría descolocada y esto
            // saldría con cabeceras equivocadas o rompería la conexión.
            Check.equal("y la conexión sigue leyendo bien HPACK después",
                    c.respuestaDe(c.pedir("GET", "/")).texto(), "hola por HTTP/2.0");
        }
    }

    // ─── lo que un cliente correcto no mandaría ──────────────────────────────────────────────

    private static void hostiles() throws Exception {
        preambuloRoto();
        rompeLaConexion("SETTINGS sobre un flujo, que no existe",
                c -> c.trama(Http2Cliente.SETTINGS, 0, 3, new byte[0]), 0x1);
        rompeLaConexion("SETTINGS con un tamaño que no es múltiplo de seis",
                c -> c.trama(Http2Cliente.SETTINGS, 0, 0, new byte[5]), 0x6);
        rompeLaConexion("DATA sobre el flujo 0",
                c -> c.trama(Http2Cliente.DATA, 0, 0, new byte[] { 1 }), 0x1);
        rompeLaConexion("HEADERS sobre el flujo 0",
                c -> c.trama(Http2Cliente.HEADERS, Http2Cliente.FIN_CABECERAS, 0,
                        c.cabeceras(":method", "GET", ":scheme", "http", ":path", "/")), 0x1);
        rompeLaConexion("HEADERS sobre un flujo par, que es de los que empuja el servidor",
                c -> c.trama(Http2Cliente.HEADERS, Http2Cliente.FIN_CABECERAS, 2,
                        c.cabeceras(":method", "GET", ":scheme", "http", ":path", "/")), 0x1);
        rompeLaConexion("un identificador de flujo hacia atrás", c -> {
            c.pedir("GET", "/");
            c.trama(Http2Cliente.HEADERS, Http2Cliente.FIN_CABECERAS | Http2Cliente.FIN_FLUJO, 1,
                    c.cabeceras(":method", "GET", ":scheme", "http", ":path", "/"));
        }, 0x1);
        rompeLaConexion("CONTINUATION sin un HEADERS delante",
                c -> c.trama(Http2Cliente.CONTINUATION, Http2Cliente.FIN_CABECERAS, 1,
                        new byte[] { (byte) 0x82 }), 0x1);
        rompeLaConexion("un cliente que intenta empujar",
                c -> c.trama(Http2Cliente.PUSH_PROMISE, 0, 1, new byte[8]), 0x1);
        rompeLaConexion("PING que no mide ocho octetos",
                c -> c.trama(Http2Cliente.PING, 0, 0, new byte[4]), 0x1);
        rompeLaConexion("PING sobre un flujo",
                c -> c.trama(Http2Cliente.PING, 0, 1, new byte[8]), 0x1);
        rompeLaConexion("WINDOW_UPDATE de tamaño imposible",
                c -> c.trama(Http2Cliente.WINDOW_UPDATE, 0, 0, new byte[3]), 0x6);
        rompeLaConexion("WINDOW_UPDATE con incremento cero sobre la conexión",
                c -> c.trama(Http2Cliente.WINDOW_UPDATE, 0, 0, Http2Cliente.entero(0)), 0x1);
        rompeLaConexion("una trama mayor que el máximo declarado",
                c -> c.tramaConLargoFalso(Http2Cliente.DATA, 0, 1, 0xFFFFFF, new byte[0]), 0x6);
        rompeLaConexion("un índice de HPACK que no existe",
                c -> c.trama(Http2Cliente.HEADERS, Http2Cliente.FIN_CABECERAS, 1,
                        new byte[] { (byte) 0xFF, 0x00 }), 0x9);
        rompeLaConexion("EOS dentro de una cadena de Huffman",
                c -> c.trama(Http2Cliente.HEADERS, Http2Cliente.FIN_CABECERAS, 1,
                        new byte[] { 0x00, 0x00, (byte) 0x84,
                                     (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF }), 0x9);

        inundaciones();

        cortaElFlujoNoLaConexion("un método que no existe",
                c -> c.cabeceras(":method", "INVENTADO", ":scheme", "http", ":path", "/"));
        cortaElFlujoNoLaConexion("una cabecera en mayúsculas, que en h2 es malformada",
                c -> c.cabecerasCrudas(":method", "GET", ":scheme", "http", ":path", "/",
                        "Content-Type", "text/plain"));
        cortaElFlujoNoLaConexion("sin :scheme",
                c -> c.cabeceras(":method", "GET", ":path", "/"));
        cortaElFlujoNoLaConexion("sin :path",
                c -> c.cabeceras(":method", "GET", ":scheme", "http"));
        cortaElFlujoNoLaConexion("con :path vacío",
                c -> c.cabeceras(":method", "GET", ":scheme", "http", ":path", ""));
        cortaElFlujoNoLaConexion("un pseudo-campo repetido",
                c -> c.cabeceras(":method", "GET", ":method", "POST", ":scheme", "http",
                        ":path", "/"));
        cortaElFlujoNoLaConexion("un pseudo-campo detrás de uno normal",
                c -> c.cabeceras(":method", "GET", ":scheme", "http", "accept", "*/*",
                        ":path", "/"));
        cortaElFlujoNoLaConexion("un pseudo-campo que no existe",
                c -> c.cabeceras(":method", "GET", ":scheme", "http", ":path", "/",
                        ":inventado", "x"));
        cortaElFlujoNoLaConexion("la cabecera connection, que en h2 está prohibida",
                c -> c.cabeceras(":method", "GET", ":scheme", "http", ":path", "/",
                        "connection", "keep-alive"));
        cortaElFlujoNoLaConexion("un TE que no sea trailers",
                c -> c.cabeceras(":method", "GET", ":scheme", "http", ":path", "/",
                        "te", "gzip"));
    }

    /**
     * Las cuatro inundaciones conocidas de HTTP/2.
     *
     * <p>Todas son la misma idea: el protocolo deja que un cliente pida trabajo al servidor sin
     * coste propio. No son entradas malformadas —cada trama es válida por separado— así que no
     * las caza ningún control de sintaxis: hacen falta topes explícitos.
     *
     * <p>Se espera ENHANCE_YOUR_CALM (0xb) y no PROTOCOL_ERROR, que es lo que distingue «te estás
     * pasando» de «esto está mal escrito».
     */
    private static void inundaciones() {
        // CVE-2024-27316: HEADERS y luego CONTINUATION sin fin. Cada trama es válida; el bloque
        // crece en memoria hasta agotarla.
        // Tramas diminutas: lo que agota no es la memoria de golpe sino la cuenta, y así el
        // cliente termina de escribir antes de que el servidor cierre — si no, el fallo sería un
        // «broken pipe» al escribir y no se llegaría a leer el GOAWAY que sí manda.
        rompeLaConexion("inundación de CONTINUATION · CVE-2024-27316", c -> {
            c.trama(Http2Cliente.HEADERS, 0, 1,
                    c.cabeceras(":method", "GET", ":scheme", "http", ":path", "/"));
            for (int i = 0; i < 80; i++) {
                c.trama(Http2Cliente.CONTINUATION, 0, 1, new byte[4]);
            }
        }, 0xb);

        // CVE-2023-44487, «Rapid Reset»: abrir y anular deja el flujo fuera del tope de
        // concurrencia al instante, así que se puede pedir trabajo sin límite.
        rompeLaConexion("Rapid Reset · CVE-2023-44487", c -> {
            for (int i = 0; i < 200; i++) {
                int flujo = c.pedirSinCerrar("GET", "/lento");
                c.trama(Http2Cliente.RST_STREAM, 0, flujo, Http2Cliente.entero(0x8));
            }
        }, 0xb);

        // Amplificación por tramas de control. Se usa WINDOW_UPDATE y no PING a propósito: el
        // PING obliga al servidor a contestar, y con 1 500 sin leer las respuestas se llenan los
        // dos buffers y lo que falla es el socket, no el tope que se quiere probar.
        rompeLaConexion("inundación de tramas de control", c -> {
            for (int i = 0; i < 1100; i++) {
                c.trama(Http2Cliente.WINDOW_UPDATE, 0, 0, Http2Cliente.entero(1));
            }
        }, 0xb);

        // Bomba de HPACK: 3 KB en el cable, 300 KB al descomprimirse. Limitar el bloque
        // comprimido no la ve — hay que mirar lo que sale.
        rompeLaConexion("bomba de expansión en HPACK", c ->
                c.trama(Http2Cliente.HEADERS,
                        Http2Cliente.FIN_CABECERAS | Http2Cliente.FIN_FLUJO, 1,
                        c.bombaHpack(3000, 100)), 0xb);
    }

    /** Un preámbulo que no es el preámbulo tiene que cerrar, no quedarse esperando. */
    private static void preambuloRoto() {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.bytes("PRI * HTTP/2.0\r\n\r\nXX\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            try {
                c.leerTrama();
                Check.that("un preámbulo falso no se atiende", false);
            } catch (IOException esperado) {
                Check.that("un preámbulo falso no se atiende", true);
            }
        } catch (IOException fallo) {
            Check.that("un preámbulo falso no se atiende", true);
        }
    }

    private interface Gesto {
        void hacer(Http2Cliente cliente) throws IOException;
    }

    private interface Bloque {
        byte[] armar(Http2Cliente cliente);
    }

    /** Manda algo y espera un GOAWAY con el código exacto que pide el RFC. */
    private static void rompeLaConexion(String nombre, Gesto gesto, int codigoEsperado) {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.saludar();
            gesto.hacer(c);
            Http2Cliente.Trama adios = c.esperar(Http2Cliente.GOAWAY);
            Check.equal("rompe la conexión · " + nombre, adios.codigoDeError(), codigoEsperado);
        } catch (IOException fallo) {
            Check.that("rompe la conexión · " + nombre + " (se esperaba GOAWAY, cerró: "
                    + fallo.getMessage() + ")", false);
        }
    }

    /**
     * Una petición malformada corta su flujo y deja la conexión en pie: el resto de flujos de ese
     * mismo cliente no tienen la culpa. Se comprueba pidiendo algo bueno después.
     */
    private static void cortaElFlujoNoLaConexion(String nombre, Bloque bloque) {
        try (Http2Cliente c = new Http2Cliente(puerto)) {
            c.saludar();
            c.trama(Http2Cliente.HEADERS,
                    Http2Cliente.FIN_CABECERAS | Http2Cliente.FIN_FLUJO, 1, bloque.armar(c));
            Http2Cliente.Trama corte = c.esperar(Http2Cliente.RST_STREAM, Http2Cliente.GOAWAY);
            if (corte.tipo() == Http2Cliente.GOAWAY) {
                Check.that("corta el flujo y no la conexión · " + nombre
                        + " (cerró la conexión entera)", false);
                return;
            }
            Check.equal("corta el flujo y no la conexión · " + nombre, corte.flujo(), 1);

            // Y la conexión sigue sirviendo: se pide algo bueno por otro flujo.
            c.trama(Http2Cliente.HEADERS,
                    Http2Cliente.FIN_CABECERAS | Http2Cliente.FIN_FLUJO, 3,
                    c.cabeceras(":method", "GET", ":scheme", "http", ":path", "/",
                            ":authority", "127.0.0.1"));
            Check.equal("y sigue atendiendo después · " + nombre,
                    c.respuestaDe(3).estado(), 200);
        } catch (IOException fallo) {
            Check.that("corta el flujo y no la conexión · " + nombre + " ("
                    + fallo.getMessage() + ")", false);
        }
    }
}
