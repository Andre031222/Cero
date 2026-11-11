package cero.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class PlazosTests {

    private static final int ANULADO = 0x8;
    private static final int CALMA = 0xb;

    private PlazosTests() {
    }

    static void run() throws Exception {
        Check.group("Plazos y HTTP/2 Bomb");

        ServerOptions opciones = ServerOptions.builder().host("127.0.0.1").port(0)
                .idleTimeoutMillis(400).writeTimeoutMillis(400).build();
        Server servidor = Server.start(opciones, PlazosTests::rutas, ErrorReporter.silent());
        try {
            ventanaACeroConGoteo(servidor.port());
            pingsSinTrabajo(servidor.port());
            presupuesto(servidor.port());
            keepAliveOcioso(servidor.port());
            descargaLarga(servidor.port());
        } finally {
            servidor.close();
        }
        ServerOptions estrechas = ServerOptions.builder().host("127.0.0.1").port(0)
                .http2MaxEnVuelo(1 << 20).maxBodyBytes(100).build();
        try (Server estrecho = Server.start(estrechas, PlazosTests::rutas, ErrorReporter.silent())) {
            unaSolaPasaDelPresupuesto(estrecho.port());
            anularYVolverAPedir(estrecho.port());
            cuerpoDeMas(estrecho.port());
        }
        try (Server cortes = Server.start(ServerOptions.builder().host("127.0.0.1").port(0).maxBodyBytes(100).build(),
                PlazosTests::rutas, ErrorReporter.silent())) {
            cortesNoGastanVentana(cortes.port());
        }
        plazosNoValidos();
    }

    private static void unaSolaPasaDelPresupuesto(int puerto) throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto).saludar()) {
            Http2Cliente.Respuesta r = c.respuestaDe(c.pedir("GET", "/grande"));
            Check.equal("H2-054: con nada más en vuelo, 4 MiB salen enteros con un presupuesto de 1 MiB",
                    r.cuerpo().length, 4 << 20);
        }
    }

    private static void anularYVolverAPedir(int puerto) throws Exception {
        try (Http2Cliente c = sinVentana(puerto)) {
            int primero = c.pedir("GET", "/grande");
            Thread.sleep(200);
            c.trama(Http2Cliente.RST_STREAM, 0, primero, Http2Cliente.entero(ANULADO));
            c.abrirVentanas(8 << 20);
            Http2Cliente.Respuesta r = c.respuestaDe(c.pedir("GET", "/grande"));
            Check.equal("H2-054: anular una descarga y pedir otra por la misma conexión no es pasarse del presupuesto",
                    r.cuerpo().length, 4 << 20);
        }
    }

    private static void cuerpoDeMas(int puerto) throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto).saludar()) {
            int flujo = c.pedirSinCerrar("POST", "/eco");
            c.trama(Http2Cliente.DATA, 0, flujo, new byte[60]);
            c.trama(Http2Cliente.DATA, 0, flujo, new byte[60]);
            Check.equal("H2-055: pasar de 100 octetos de cuerpo es 413", c.respuestaDe(flujo).estado(), 413);
            Check.equal("H2-055: y RST_STREAM NO_ERROR para que deje de mandar",
                    c.esperar(Http2Cliente.RST_STREAM).codigoDeError(), 0);

            int anunciado = c.pedirSinCerrar("POST", "/eco", "content-length", "1000");
            Check.equal("H2-055: un content-length de más es 413 antes de mandar nada y de correr la acción",
                    c.respuestaDe(anunciado).estado(), 413);
            Check.equal("H2-055: también con RST_STREAM NO_ERROR",
                    c.esperar(Http2Cliente.RST_STREAM).codigoDeError(), 0);

            int sinLeer = c.pedirSinCerrar("POST", "/");
            Check.equal("una acción que no lee el cuerpo responde antes", c.respuestaDe(sinLeer).estado(), 200);
            c.trama(Http2Cliente.DATA, 0, sinLeer, new byte[60]);
            c.trama(Http2Cliente.DATA, 0, sinLeer, new byte[60]);
            Http2Cliente.Trama corte = c.esperar(Http2Cliente.RST_STREAM);
            Check.that("H2-055: con la respuesta ya dada, pasar del tope es RST_STREAM NO_ERROR",
                    corte.flujo() == sinLeer && corte.codigoDeError() == 0);
        }
    }

    private static void cortesNoGastanVentana(int puerto) throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto).saludar()) {
            for (int i = 0; i < 3; i++) {
                int flujo = c.pedirSinCerrar("POST", "/grande");
                int recibidos = 0;
                while (recibidos < 65_535) {
                    Http2Cliente.Trama t = c.leerTrama();
                    if (t.tipo() == Http2Cliente.DATA) {
                        recibidos += t.carga().length;
                    }
                }
                c.trama(Http2Cliente.WINDOW_UPDATE, 0, flujo, Http2Cliente.entero(1_000));
                c.trama(Http2Cliente.WINDOW_UPDATE, 0, 0, Http2Cliente.entero(1_000));
                c.trama(Http2Cliente.DATA, 0, flujo, new byte[120]);
                for (Http2Cliente.Trama t = c.leerTrama(); t.tipo() != Http2Cliente.RST_STREAM; t = c.leerTrama()) {
                    if (t.tipo() == Http2Cliente.DATA) {
                        recibidos += t.carga().length;
                    }
                }
                c.trama(Http2Cliente.WINDOW_UPDATE, 0, 0, Http2Cliente.entero(recibidos));
            }
            int flujo = c.pedir("GET", "/justo");
            int recibidos = 0;
            boolean fin = false;
            while (!fin) {
                Http2Cliente.Trama t = c.leerTrama();
                if (t.tipo() == Http2Cliente.RST_STREAM && t.flujo() == flujo) {
                    break;
                }
                if (t.tipo() == Http2Cliente.DATA && t.flujo() == flujo) {
                    recibidos += t.carga().length;
                    fin = (t.banderas() & Http2Cliente.FIN_FLUJO) != 0;
                    c.trama(Http2Cliente.WINDOW_UPDATE, 0, flujo, Http2Cliente.entero(t.carga().length));
                }
            }
            Check.equal("H2-055: cortar flujos a mitad de escritura no gasta ventana de la conexión para siempre",
                    recibidos, 65_535 + 3 * 1_000);
        }
    }

    private static void descargaLarga(int puerto) throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto).saludar()) {
            Check.equal("H2-038: 16 MiB con un WINDOW_UPDATE por trama no son una inundación",
                    c.respuestaDe(c.pedir("GET", "/descarga")).cuerpo().length, 16 << 20);
        }
    }

    private static void rutas(Request peticion, Response respuesta) throws Exception {
        switch (peticion.path()) {
            case "/grande" -> respuesta.text("x".repeat(4 << 20));
            case "/eco" -> respuesta.send(peticion.bodyBytes());
            case "/descarga" -> respuesta.text("x".repeat(16 << 20));
            case "/justo" -> respuesta.text("x".repeat(65_535 + 3 * 1_000));
            default -> respuesta.text("x".repeat(100));
        }
    }

    private static Http2Cliente sinVentana(int puerto) throws IOException {
        var c = new Http2Cliente(puerto).preambulo();
        c.trama(Http2Cliente.SETTINGS, 0, 0, new byte[] { 0, 4, 0, 0, 0, 0 });
        return c;
    }

    private static Thread latir(Http2Cliente c, AtomicBoolean parar, int tipo, int flujo, byte[] carga) {
        return Thread.ofVirtual().start(() -> {
            try {
                while (!parar.get()) {
                    c.trama(tipo, 0, flujo, carga);
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException seAcabo) {
            }
        });
    }

    private static void ventanaACeroConGoteo(int puerto) throws Exception {
        try (Http2Cliente c = sinVentana(puerto)) {
            c.pedir("GET", "/");
            var parar = new AtomicBoolean();
            Thread goteo = latir(c, parar, Http2Cliente.WINDOW_UPDATE, 1, Http2Cliente.entero(1));
            int datos = 0;
            Http2Cliente.Trama corte = null;
            while (corte == null) {
                Http2Cliente.Trama t = c.leerTrama();
                if (t.tipo() == Http2Cliente.DATA && t.flujo() == 1) {
                    datos += t.carga().length;
                }
                if (t.tipo() == Http2Cliente.RST_STREAM && t.flujo() == 1) {
                    corte = t;
                }
            }
            parar.set(true);
            goteo.join();
            Check.equal("H2-052: un octeto de crédito cada 50 ms no salva una trama en 400 ms",
                    corte.codigoDeError(), ANULADO);
            Check.that("H2-052: salió lo que cabía, no la respuesta entera", datos > 0 && datos < 100);
        }
    }

    private static void pingsSinTrabajo(int puerto) throws Exception {
        try (Http2Cliente c = new Http2Cliente(puerto).saludar()) {
            var parar = new AtomicBoolean();
            Thread latido = Thread.ofVirtual().start(() -> {
                try {
                    while (!parar.get()) {
                        c.trama(Http2Cliente.PING, 0, 0, "12345678".getBytes(StandardCharsets.US_ASCII));
                        c.trama(Http2Cliente.WINDOW_UPDATE, 0, 0, Http2Cliente.entero(1));
                        c.trama(Http2Cliente.SETTINGS, 0, 0, new byte[0]);
                        c.trama(Http2Cliente.PRIORITY, 0, 3, new byte[] { 0, 0, 0, 0, 15 });
                        Thread.sleep(100);
                    }
                } catch (IOException | InterruptedException seAcabo) {
                }
            });
            long empezo = System.nanoTime();
            Http2Cliente.Trama adios = c.esperar(Http2Cliente.GOAWAY);
            long tardo = (System.nanoTime() - empezo) / 1_000_000;
            parar.set(true);
            latido.join();
            Check.equal("H2-053: PING, WINDOW_UPDATE, SETTINGS y PRIORITY no son trabajo, y la conexión se despide",
                    adios.codigoDeError(), 0);
            Check.that("H2-053: dentro del plazo de inactividad y no antes (" + tardo + " ms)",
                    tardo >= 300 && tardo < 2_000);
        }
    }

    private static void presupuesto(int puerto) throws Exception {
        try (Http2Cliente c = sinVentana(puerto)) {
            for (int i = 0; i < 10; i++) {
                c.pedir("GET", "/grande");
            }
            Check.equal("H2-054: 40 MiB construidos y sin salir pasan de los 32: ENHANCE_YOUR_CALM",
                    c.esperar(Http2Cliente.GOAWAY).codigoDeError(), CALMA);
        }
    }

    private static void keepAliveOcioso(int puerto) throws Exception {
        try (Socket s = new Socket("127.0.0.1", puerto)) {
            s.setSoTimeout(5_000);
            byte[] pedido = "GET / HTTP/1.1\r\nHost: a\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            InputStream in = s.getInputStream();
            s.getOutputStream().write(pedido);
            String primera = Fixture.readHead(in);
            in.readNBytes(100);
            Check.that("HTTP-024: la primera petición se atiende y la conexión sigue",
                    primera.startsWith("HTTP/1.1 200") && !primera.toLowerCase().contains("connection: close"));
            Thread.sleep(200);
            s.getOutputStream().write(pedido);
            Check.that("HTTP-024: una segunda a media inactividad se atiende por la misma conexión",
                    Fixture.readHead(in).startsWith("HTTP/1.1 200"));
            in.readNBytes(100);
            long empezo = System.nanoTime();
            byte[] resto = in.readAllBytes();
            long tardo = (System.nanoTime() - empezo) / 1_000_000;
            Check.that("HTTP-024: callar después cierra la conexión, dentro del plazo y no antes (" + tardo + " ms)",
                    resto.length == 0 && tardo >= 300 && tardo < 2_000);
        }
    }

    private static void plazosNoValidos() {
        for (int malo : new int[] { 0, -1 }) {
            Check.that("HTTP-025: idleTimeoutMillis(" + malo + ") no arranca",
                    rechazado(ServerOptions.builder().idleTimeoutMillis(malo)));
            Check.that("HTTP-025: handlerTimeoutMillis(" + malo + ") no arranca",
                    rechazado(ServerOptions.builder().handlerTimeoutMillis(malo)));
            Check.that("HTTP-025: writeTimeoutMillis(" + malo + ") no arranca",
                    rechazado(ServerOptions.builder().writeTimeoutMillis(malo)));
            Check.that("HTTP-025: http2MaxEnVuelo(" + malo + ") no arranca",
                    rechazado(ServerOptions.builder().http2MaxEnVuelo(malo)));
            Check.that("HTTP-025: maxBodyBytes(" + malo + ") no arranca",
                    rechazado(ServerOptions.builder().maxBodyBytes(malo)));
        }
    }

    private static boolean rechazado(ServerOptions.Builder opciones) {
        try {
            opciones.build();
            return false;
        } catch (IllegalArgumentException esperado) {
            return true;
        }
    }
}
