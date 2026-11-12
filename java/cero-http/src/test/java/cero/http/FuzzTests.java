package cero.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

final class FuzzTests {

    private FuzzTests() {
    }

    private static final long SEMILLA = 20260905L;
    private static final int CASOS = 400;

    static void run() throws Exception {
        Check.group("ruido dirigido");

        ServerOptions opciones = ServerOptions.builder()
                .host("127.0.0.1").port(0).idleTimeoutMillis(1_500).build();
        try (Server servidor = Server.start(opciones,
                (peticion, respuesta) -> respuesta.text("ok"), ErrorReporter.silent())) {
            int puerto = servidor.port();
            ruidoHttp1(puerto);
            ruidoDeTramas(puerto);
            ruidoTrasElPreambulo(puerto);

            Check.equal("después de " + (CASOS * 3) + " casos, el servidor sigue respondiendo",
                    Fixture.get("http://127.0.0.1:" + puerto + "/").statusCode(), 200);
            Check.that("y sigue habiendo un solo servidor, no hilos sueltos",
                    servidor.running());
        }
    }

    private static void ruidoHttp1(int puerto) {
        var azar = new Random(SEMILLA);
        String[] plantillas = {
                "GET / HTTP/1.1\r\nHost: x\r\n\r\n",
                "POST /eco HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhola\n",
                "GET / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhola\r\n0\r\n\r\n",
                "GET /?a=1&b=2 HTTP/1.1\r\nHost: x\r\nCookie: s=1\r\nAccept: */*\r\n\r\n",
                "OPTIONS * HTTP/1.1\r\nHost: x\r\n\r\n",
        };
        int caidas = 0;
        for (int i = 0; i < CASOS; i++) {
            byte[] crudo = plantillas[azar.nextInt(plantillas.length)]
                    .getBytes(StandardCharsets.ISO_8859_1);
            crudo = estropear(crudo, azar);
            if (!aguanta(puerto, crudo)) {
                caidas++;
            }
        }
        Check.equal("HTTP/1.1 · " + CASOS + " peticiones estropeadas sin tumbar el servidor",
                caidas, 0);
    }

    private static void ruidoDeTramas(int puerto) {
        var azar = new Random(SEMILLA + 1);
        int caidas = 0;
        for (int i = 0; i < CASOS; i++) {
            var fuera = new ByteArrayOutputStream();
            fuera.writeBytes(Http2.PREAMBULO);
            for (int t = 0; t < 1 + azar.nextInt(4); t++) {
                int largo = azar.nextInt(64);
                byte[] carga = new byte[largo];
                azar.nextBytes(carga);
                fuera.write((largo >>> 16) & 0xFF);
                fuera.write((largo >>> 8) & 0xFF);
                fuera.write(largo & 0xFF);
                fuera.write(azar.nextInt(16));
                fuera.write(azar.nextInt(256));
                fuera.write(azar.nextInt(2));
                fuera.write(azar.nextInt(256));
                fuera.write(azar.nextInt(256));
                fuera.write(azar.nextInt(256));
                fuera.writeBytes(carga);
            }
            if (!aguanta(puerto, fuera.toByteArray())) {
                caidas++;
            }
        }
        Check.equal("HTTP/2 · " + CASOS + " ráfagas de tramas al azar sin tumbar el servidor",
                caidas, 0);
    }

    private static void ruidoTrasElPreambulo(int puerto) {
        var azar = new Random(SEMILLA + 2);
        int caidas = 0;
        for (int i = 0; i < CASOS; i++) {
            byte[] basura = new byte[azar.nextInt(512)];
            azar.nextBytes(basura);
            var fuera = new ByteArrayOutputStream();
            fuera.writeBytes(Http2.PREAMBULO);
            fuera.writeBytes(basura);
            if (!aguanta(puerto, fuera.toByteArray())) {
                caidas++;
            }
        }
        Check.equal("HTTP/2 · " + CASOS + " ráfagas de bytes puros sin tumbar el servidor",
                caidas, 0);
    }

    private static byte[] estropear(byte[] original, Random azar) {
        byte[] copia = original.clone();
        switch (azar.nextInt(4)) {
            case 0 -> copia[azar.nextInt(copia.length)] = (byte) azar.nextInt(256);
            case 1 -> {
                int corte = azar.nextInt(copia.length);
                copia = Arrays.copyOfRange(copia, 0, corte);
            }
            case 2 -> {
                byte[] doble = new byte[copia.length * 2];
                System.arraycopy(copia, 0, doble, 0, copia.length);
                System.arraycopy(copia, 0, doble, copia.length, copia.length);
                copia = doble;
            }
            default -> {
                for (int i = 0; i < 4; i++) {
                    copia[azar.nextInt(copia.length)] = (byte) azar.nextInt(256);
                }
            }
        }
        return copia;
    }

    private static boolean aguanta(int puerto, byte[] crudo) {
        try (var socket = new Socket("127.0.0.1", puerto)) {
            socket.setSoTimeout(1_500);
            OutputStream out = socket.getOutputStream();
            out.write(crudo);
            out.flush();
            try {
                socket.getInputStream().read(new byte[512]);
            } catch (IOException cerroOTardo) {
            }
            return true;
        } catch (IOException noSePudoConectar) {
            return false;
        }
    }
}
