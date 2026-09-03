package cero.http;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class WebSocketTests {

    private WebSocketTests() {
    }

    static void run() throws Exception {
        Check.group("WebSocket");

        apretonDeManos();
        apretonRechazado();
        ecoDeTexto();
        mensajeFragmentado();
        pingResponde();
        cierreOrdenado();
        clienteSinMascara();
        mensajeDemasiadoGrande();
        origen();
        textoMalFormado();
        longitudNoMinima();
    }

    /** Otra web no puede abrir el canal con las cookies del visitante. */
    private static void origen() throws Exception {
        Check.group("WebSocket · origen");

        try (Server server = servidor(new WebSocketHandler() {
        })) {
            Check.that("un origen ajeno se rechaza con 403",
                    conOrigen(server.port(), "https://mala.pe").startsWith("HTTP/1.1 403"));

            // El cuerpo nombra Origin y Host: sin eso, un proxy que reescribe Host se depura a ciegas.
            String rechazo = Fixture.raw(server.port(), "GET /ws HTTP/1.1\r\nHost: x\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Origin: https://mala.pe\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n\r\n");
            Check.that("y el mensaje nombra el origen", rechazo.contains("mala.pe"));
            Check.that("y el Host con el que se comparó", rechazo.contains("Host: x"));

            Check.that("otro puerto del mismo host también es otro origen",
                    conOrigen(server.port(), "http://x:9999").startsWith("HTTP/1.1 403"));
            Check.that("el mismo origen entra",
                    conOrigen(server.port(), "http://x").startsWith("HTTP/1.1 101"));
            Check.that("sin Origin entra: no hay navegador de por medio",
                    conOrigen(server.port(), null).startsWith("HTTP/1.1 101"));
            Check.that("un Origin vacío se trata como ausente",
                    conOrigen(server.port(), "").startsWith("HTTP/1.1 101"));
            Check.that("el origen «null» de un iframe con sandbox se rechaza",
                    conOrigen(server.port(), "null").startsWith("HTTP/1.1 403"));
            Check.that("las mayúsculas del host no importan",
                    conOrigen(server.port(), "http://X").startsWith("HTTP/1.1 101"));
            Check.that("tras un rechazo el servidor sigue atendiendo",
                    conOrigen(server.port(), null).startsWith("HTTP/1.1 101"));
        }

        try (Server declarado = servidorConOrigenes("https://panel.ginit.dev")) {
            Check.that("un origen declarado entra",
                    conOrigen(declarado.port(), "https://panel.ginit.dev").startsWith("HTTP/1.1 101"));
            Check.that("uno parecido pero distinto no",
                    conOrigen(declarado.port(), "https://panel.ginit.dev.mala.pe")
                            .startsWith("HTTP/1.1 403"));
            Check.that("y el mismo origen sigue entrando",
                    conOrigen(declarado.port(), "http://x").startsWith("HTTP/1.1 101"));
        }
    }

    /** 6455 §8.1: un marco de texto que no es UTF-8 válido se cierra con 1007. */
    private static void textoMalFormado() throws Exception {
        Check.group("WebSocket · texto mal formado");

        List<String> recibidos = new CopyOnWriteArrayList<>();
        WebSocketHandler oyente = new WebSocketHandler() {
            @Override
            public void onMessage(WebSocket socket, String texto) {
                recibidos.add(texto);
            }
        };

        // 0xC3 abre una secuencia de dos bytes y 0x28 no la continúa.
        byte[][] rotos = {
                {(byte) 0xC3, 0x28},
                {(byte) 0xA0, (byte) 0xA1},
                {(byte) 0xE2, 0x28, (byte) 0xA1},
                {(byte) 0xF0, (byte) 0x28, (byte) 0x8C, (byte) 0x28},
                {(byte) 0xFF},
                {(byte) 0xC3},
        };
        for (byte[] roto : rotos) {
            try (Server server = servidor(oyente); Socket socket = abrir(server.port())) {
                escribirMarco(socket.getOutputStream(), 0x01, true, roto);
                socket.getOutputStream().flush();

                Marco respuesta = recibirMarco(socket);
                Check.equal("se cierra la conexión", respuesta.codigo, 8);
                Check.equal("con el código de datos inválidos", codigoDeCierre(respuesta), 1007);
            }
        }
        Check.equal("y nada de eso llegó a la aplicación", recibidos.size(), 0);

        try (Server server = servidor(new WebSocketHandler() {
            @Override
            public void onMessage(WebSocket socket, String texto) {
                socket.send(texto);
            }
        }); Socket socket = abrir(server.port())) {
            enviarTexto(socket, "ñandú · 中文 · 🜏");
            Check.equal("el UTF-8 válido pasa entero", recibirTexto(socket), "ñandú · 中文 · 🜏");
        }
    }

    /** 6455 §5.2: la longitud va en su forma más corta, o hay dos marcos para un mismo mensaje. */
    private static void longitudNoMinima() throws Exception {
        Check.group("WebSocket · longitud no mínima");

        try (Server server = servidor(new WebSocketHandler() {
        }); Socket socket = abrir(server.port())) {
            OutputStream salida = socket.getOutputStream();
            salida.write(0x81);
            salida.write(0x80 | 126);
            salida.write(0);
            salida.write(5);
            salida.write(new byte[] {0, 0, 0, 0});
            salida.write("hola!".getBytes(StandardCharsets.UTF_8));
            salida.flush();

            Marco respuesta = recibirMarco(socket);
            Check.equal("16 bits para algo que cabe en 7 se rechaza", respuesta.codigo, 8);
            Check.equal("con error de protocolo", codigoDeCierre(respuesta), 1002);
        }

        try (Server server = servidor(new WebSocketHandler() {
        }); Socket socket = abrir(server.port())) {
            OutputStream salida = socket.getOutputStream();
            salida.write(0x81);
            salida.write(0x80 | 127);
            for (int i = 0; i < 7; i++) {
                salida.write(0);
            }
            salida.write(5);
            salida.write(new byte[] {0, 0, 0, 0});
            salida.write("hola!".getBytes(StandardCharsets.UTF_8));
            salida.flush();

            Marco respuesta = recibirMarco(socket);
            Check.equal("64 bits para algo que cabe en 16 también", respuesta.codigo, 8);
            Check.equal("con error de protocolo", codigoDeCierre(respuesta), 1002);
        }
    }

    private static Server servidorConOrigenes(String... admitidos) {
        return Server.start(ServerOptions.builder().port(0).handlerTimeoutMillis(0).build(),
                (peticion, respuesta) -> WebSockets.accept(peticion, respuesta,
                        new WebSocketHandler() {
                        }, java.util.Set.of(admitidos)),
                ErrorReporter.silent());
    }

    /** Pide el cambio declarando ese Origin y devuelve la cabecera de respuesta. */
    private static String conOrigen(int puerto, String origen) throws IOException {
        try (Socket socket = Fixture.connect(puerto)) {
            byte[] claveCruda = new byte[16];
            new SecureRandom().nextBytes(claveCruda);
            String peticion = "GET /ws HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + (origen == null ? "" : "Origin: " + origen + "\r\n")
                    + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(claveCruda) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            socket.getOutputStream().write(peticion.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return Fixture.readHead(socket.getInputStream());
        }
    }

    // ── servidor de prueba ───────────────────────────────────────────────────

    private static Server servidor(WebSocketHandler handler) {
        return Server.start(ServerOptions.builder().port(0).handlerTimeoutMillis(0).build(),
                (peticion, respuesta) -> {
                    if (peticion.path().equals("/ws")) {
                        WebSockets.accept(peticion, respuesta, handler);
                    } else {
                        respuesta.text("no es aquí");
                    }
                },
                ErrorReporter.silent());
    }

    private static void apretonDeManos() throws Exception {
        try (Server server = servidor(new WebSocketHandler() {
        })) {
            byte[] claveCruda = new byte[16];
            new SecureRandom().nextBytes(claveCruda);
            String clave = Base64.getEncoder().encodeToString(claveCruda);

            try (Socket socket = Fixture.connect(server.port())) {
                pedirCambio(socket, clave);
                String cabecera = Fixture.readHead(socket.getInputStream());

                Check.that("responde 101", cabecera.startsWith("HTTP/1.1 101"));
                Check.equal("confirma el cambio a websocket",
                        Fixture.headerOf(cabecera, "Upgrade").toLowerCase(), "websocket");
                Check.equal("devuelve la clave firmada",
                        Fixture.headerOf(cabecera, "Sec-WebSocket-Accept"), WebSockets.aceptacion(clave));
            }
        }
    }

    private static void apretonRechazado() throws Exception {
        try (Server server = servidor(new WebSocketHandler() {
        })) {
            String sinClave = Fixture.raw(server.port(),
                    "GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n");
            Check.that("sin Sec-WebSocket-Key da 400", sinClave.startsWith("HTTP/1.1 400"));

            String versionVieja = Fixture.raw(server.port(),
                    "GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Key: abc\r\nSec-WebSocket-Version: 8\r\n\r\n");
            Check.that("una versión distinta de 13 da 426", versionVieja.startsWith("HTTP/1.1 426"));

            String sinCambio = Fixture.raw(server.port(), "GET /ws HTTP/1.1\r\nHost: x\r\n\r\n");
            Check.that("una petición normal a la ruta da 400", sinCambio.startsWith("HTTP/1.1 400"));
        }
    }

    private static void ecoDeTexto() throws Exception {
        try (Server server = servidor(new WebSocketHandler() {
            @Override
            public void onMessage(WebSocket socket, String texto) {
                socket.send("eco:" + texto);
            }
        }); Socket socket = abrir(server.port())) {

            enviarTexto(socket, "hola");
            Check.equal("devuelve el mensaje", recibirTexto(socket), "eco:hola");

            enviarTexto(socket, "otra vez");
            Check.equal("y sigue la conversación", recibirTexto(socket), "eco:otra vez");

            String acentos = "eñe y tildes: áéíóú";
            enviarTexto(socket, acentos);
            Check.equal("el UTF-8 va y vuelve entero", recibirTexto(socket), "eco:" + acentos);
        }
    }

    private static void mensajeFragmentado() throws Exception {
        try (Server server = servidor(new WebSocketHandler() {
            @Override
            public void onMessage(WebSocket socket, String texto) {
                socket.send("junto:" + texto);
            }
        }); Socket socket = abrir(server.port())) {

            OutputStream salida = socket.getOutputStream();
            escribirMarco(salida, 0x01, false, "uno ".getBytes(StandardCharsets.UTF_8));
            escribirMarco(salida, 0x00, false, "dos ".getBytes(StandardCharsets.UTF_8));
            escribirMarco(salida, 0x00, true, "tres".getBytes(StandardCharsets.UTF_8));
            salida.flush();

            Check.equal("los tres fragmentos llegan como un mensaje",
                    recibirTexto(socket), "junto:uno dos tres");
        }
    }

    private static void pingResponde() throws Exception {
        try (Server server = servidor(new WebSocketHandler() {
        }); Socket socket = abrir(server.port())) {

            escribirMarco(socket.getOutputStream(), 0x09, true, "late".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            Marco respuesta = recibirMarco(socket);
            Check.equal("un ping recibe pong", respuesta.codigo, 10);
            Check.equal("con la misma carga", new String(respuesta.carga, StandardCharsets.UTF_8), "late");
        }
    }

    private static void cierreOrdenado() throws Exception {
        CountDownLatch cerrado = new CountDownLatch(1);
        List<String> motivos = new CopyOnWriteArrayList<>();

        try (Server server = servidor(new WebSocketHandler() {
            @Override
            public void onClose(WebSocket socket, int codigo, String motivo) {
                motivos.add(motivo);
                cerrado.countDown();
            }
        }); Socket socket = abrir(server.port())) {

            byte[] carga = new byte[2 + 6];
            carga[0] = (byte) (1000 >> 8);
            carga[1] = (byte) 1000;
            System.arraycopy("adiós".getBytes(StandardCharsets.UTF_8), 0, carga, 2, 6);
            escribirMarco(socket.getOutputStream(), 0x08, true, carga);
            socket.getOutputStream().flush();

            Marco respuesta = recibirMarco(socket);
            Check.equal("el servidor devuelve el cierre", respuesta.codigo, 8);
            Check.that("y avisa al handler", cerrado.await(3, TimeUnit.SECONDS));
            Check.equal("con el motivo que mandó el cliente", motivos, List.of("adiós"));
        }
    }

    private static void clienteSinMascara() throws Exception {
        try (Server server = servidor(new WebSocketHandler() {
        }); Socket socket = abrir(server.port())) {

            OutputStream salida = socket.getOutputStream();
            byte[] carga = "sin máscara".getBytes(StandardCharsets.UTF_8);
            salida.write(0x81);
            salida.write(carga.length);
            salida.write(carga);
            salida.flush();

            Marco respuesta = recibirMarco(socket);
            Check.equal("un marco sin enmascarar se corta", respuesta.codigo, 8);
            Check.equal("con código de error de protocolo", codigoDeCierre(respuesta), 1002);
        }
    }

    private static void mensajeDemasiadoGrande() throws Exception {
        try (Server server = servidor(new WebSocketHandler() {
        }); Socket socket = abrir(server.port())) {

            OutputStream salida = socket.getOutputStream();
            salida.write(0x81);
            salida.write(0xFF);
            long declarado = 8L << 20;
            for (int desplazamiento = 56; desplazamiento >= 0; desplazamiento -= 8) {
                salida.write((int) (declarado >> desplazamiento));
            }
            salida.flush();

            Marco respuesta = recibirMarco(socket);
            Check.equal("un mensaje enorme se rechaza sin reservar memoria", respuesta.codigo, 8);
            Check.equal("con el código de «demasiado grande»", codigoDeCierre(respuesta), 1009);
        }
    }

    // ── cliente mínimo de WebSocket ──────────────────────────────────────────

    private static Socket abrir(int puerto) throws IOException {
        Socket socket = Fixture.connect(puerto);
        byte[] claveCruda = new byte[16];
        new SecureRandom().nextBytes(claveCruda);
        pedirCambio(socket, Base64.getEncoder().encodeToString(claveCruda));
        String cabecera = Fixture.readHead(socket.getInputStream());
        if (!cabecera.startsWith("HTTP/1.1 101")) {
            socket.close();
            throw new IOException("el apretón de manos falló: " + cabecera);
        }
        return socket;
    }

    private static void pedirCambio(Socket socket, String clave) throws IOException {
        socket.getOutputStream().write(("GET /ws HTTP/1.1\r\n"
                + "Host: x\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + clave + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    private static void enviarTexto(Socket socket, String texto) throws IOException {
        escribirMarco(socket.getOutputStream(), 0x01, true, texto.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    /** Escribe un marco de cliente, que va siempre enmascarado. */
    private static void escribirMarco(OutputStream salida, int codigo, boolean fin, byte[] carga)
            throws IOException {

        salida.write((fin ? 0x80 : 0x00) | codigo);
        if (carga.length < 126) {
            salida.write(0x80 | carga.length);
        } else {
            salida.write(0x80 | 126);
            salida.write(carga.length >> 8);
            salida.write(carga.length);
        }
        byte[] mascara = {0x12, 0x34, 0x56, 0x78};
        salida.write(mascara);
        byte[] enmascarada = carga.clone();
        for (int i = 0; i < enmascarada.length; i++) {
            enmascarada[i] ^= mascara[i & 3];
        }
        salida.write(enmascarada);
    }

    private static String recibirTexto(Socket socket) throws IOException {
        return new String(recibirMarco(socket).carga, StandardCharsets.UTF_8);
    }

    private static Marco recibirMarco(Socket socket) throws IOException {
        InputStream entrada = socket.getInputStream();
        DataInputStream datos = new DataInputStream(entrada);
        int primero = datos.readUnsignedByte();
        int segundo = datos.readUnsignedByte();
        int longitud = segundo & 0x7F;
        if (longitud == 126) {
            longitud = datos.readUnsignedShort();
        } else if (longitud == 127) {
            longitud = (int) datos.readLong();
        }
        byte[] carga = new byte[longitud];
        datos.readFully(carga);
        return new Marco(primero & 0x0F, carga);
    }

    private static int codigoDeCierre(Marco marco) {
        return marco.carga.length < 2 ? 0 : ((marco.carga[0] & 0xFF) << 8) | (marco.carga[1] & 0xFF);
    }

    private record Marco(int codigo, byte[] carga) {
    }
}
