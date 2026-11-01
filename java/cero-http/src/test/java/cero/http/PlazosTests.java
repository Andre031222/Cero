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

        ServerOptions opciones = ServerOptions.builder().port(0)
                .idleTimeoutMillis(400).writeTimeoutMillis(400).build();
        Server servidor = Server.start(opciones, PlazosTests::rutas, ErrorReporter.silent());
        try {
            ventanaACeroConGoteo(servidor.port());
            pingsSinTrabajo(servidor.port());
            presupuesto(servidor.port());
            keepAliveOcioso(servidor.port());
        } finally {
            servidor.close();
        }
        plazosNoValidos();
    }

    private static void rutas(Request peticion, Response respuesta) {
        switch (peticion.path()) {
            case "/grande" -> respuesta.text("x".repeat(4 << 20));
            default -> respuesta.text("x".repeat(100));
        }
    }

    private static Http2Cliente sinVentana(int puerto) throws IOException {
        Http2Cliente c = new Http2Cliente(puerto).preambulo();
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
            AtomicBoolean parar = new AtomicBoolean();
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
            AtomicBoolean parar = new AtomicBoolean();
            Thread latido = latir(c, parar, Http2Cliente.PING, 0, "12345678".getBytes(StandardCharsets.US_ASCII));
            long empezo = System.nanoTime();
            Http2Cliente.Trama adios = c.esperar(Http2Cliente.GOAWAY);
            long tardo = (System.nanoTime() - empezo) / 1_000_000;
            parar.set(true);
            latido.join();
            Check.equal("H2-053: PING no es trabajo, y la conexión se despide", adios.codigoDeError(), 0);
            Check.that("H2-053: dentro del plazo de inactividad (" + tardo + " ms)", tardo < 2_000);
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
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: a\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            long empezo = System.nanoTime();
            byte[] todo = in.readAllBytes();
            long tardo = (System.nanoTime() - empezo) / 1_000_000;
            Check.that("HTTP-024: la primera petición se atiende",
                    new String(todo, StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200"));
            Check.that("HTTP-024: callar después cierra la conexión dentro del plazo (" + tardo + " ms)",
                    tardo < 2_000);
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
