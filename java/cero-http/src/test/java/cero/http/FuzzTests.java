package cero.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Random;

/**
 * Ruido dirigido contra los dos parsers, HTTP/1.1 y HTTP/2.
 *
 * <p>No busca comprobar una respuesta concreta: busca que <b>no haya ninguna en la que el
 * servidor se caiga, se cuelgue o se lleve por delante la conexión de otro</b>. Los vectores
 * escritos a mano comprueban lo que alguien pensó; esto comprueba lo que nadie pensó.
 *
 * <p>La semilla es fija a propósito. Un fuzzer con semilla del reloj falla un día de cada
 * cincuenta en integración continua y nadie puede reproducirlo; con semilla fija, si falla, falla
 * siempre igual y se arregla. Para buscar de verdad se sube el número de casos y se cambia la
 * semilla a mano — ahí sí interesa que cada corrida sea distinta.
 *
 * <p>El criterio de éxito es el mismo para los dos: después de todo el ruido, el servidor sigue
 * atendiendo una petición normal. Si un caso lo hubiera tumbado, esa última comprobación falla.
 */
final class FuzzTests {

    private FuzzTests() {
    }

    private static final long SEMILLA = 20260905L;
    private static final int CASOS = 400;

    static void run() throws Exception {
        Check.group("ruido dirigido");

        ServerOptions opciones = ServerOptions.builder()
                .port(0).idleTimeoutMillis(1_500).build();
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

    /** Peticiones HTTP/1.1 parecidas a las de verdad, pero rotas por un sitio al azar. */
    private static void ruidoHttp1(int puerto) {
        Random azar = new Random(SEMILLA);
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
                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            crudo = estropear(crudo, azar);
            if (!aguanta(puerto, crudo)) {
                caidas++;
            }
        }
        Check.equal("HTTP/1.1 · " + CASOS + " peticiones estropeadas sin tumbar el servidor",
                caidas, 0);
    }

    /** Tramas de HTTP/2 con tipos, banderas, flujos y longitudes al azar. */
    private static void ruidoDeTramas(int puerto) {
        Random azar = new Random(SEMILLA + 1);
        int caidas = 0;
        for (int i = 0; i < CASOS; i++) {
            java.io.ByteArrayOutputStream fuera = new java.io.ByteArrayOutputStream();
            fuera.writeBytes(Http2.PREAMBULO);
            for (int t = 0; t < 1 + azar.nextInt(4); t++) {
                int largo = azar.nextInt(64);
                byte[] carga = new byte[largo];
                azar.nextBytes(carga);
                fuera.write((largo >>> 16) & 0xFF);
                fuera.write((largo >>> 8) & 0xFF);
                fuera.write(largo & 0xFF);
                fuera.write(azar.nextInt(16));          // tipo, incluidos los que no existen
                fuera.write(azar.nextInt(256));         // banderas al azar
                fuera.write(azar.nextInt(2));           // el bit reservado, también
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

    /** Bytes puros detrás de un preámbulo válido: lo peor que puede leer la capa de tramas. */
    private static void ruidoTrasElPreambulo(int puerto) {
        Random azar = new Random(SEMILLA + 2);
        int caidas = 0;
        for (int i = 0; i < CASOS; i++) {
            byte[] basura = new byte[azar.nextInt(512)];
            azar.nextBytes(basura);
            java.io.ByteArrayOutputStream fuera = new java.io.ByteArrayOutputStream();
            fuera.writeBytes(Http2.PREAMBULO);
            fuera.writeBytes(basura);
            if (!aguanta(puerto, fuera.toByteArray())) {
                caidas++;
            }
        }
        Check.equal("HTTP/2 · " + CASOS + " ráfagas de bytes puros sin tumbar el servidor",
                caidas, 0);
    }

    /** Cambia, quita o duplica un trozo al azar. */
    private static byte[] estropear(byte[] original, Random azar) {
        byte[] copia = original.clone();
        switch (azar.nextInt(4)) {
            case 0 -> copia[azar.nextInt(copia.length)] = (byte) azar.nextInt(256);
            case 1 -> {
                int corte = azar.nextInt(copia.length);
                copia = java.util.Arrays.copyOfRange(copia, 0, corte);
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

    /**
     * Manda los bytes y mira si el servidor sobrevive.
     *
     * <p>Que el servidor cierre la conexión o no conteste es correcto —eso es rechazar—; lo que
     * no puede pasar es que deje de aceptar conexiones nuevas.
     */
    private static boolean aguanta(int puerto, byte[] crudo) {
        try (Socket socket = new Socket("127.0.0.1", puerto)) {
            socket.setSoTimeout(1_500);
            OutputStream out = socket.getOutputStream();
            out.write(crudo);
            out.flush();
            try {
                socket.getInputStream().read(new byte[512]);
            } catch (IOException cerroOTardo) {
                // Rechazar es una respuesta válida.
            }
            return true;
        } catch (IOException noSePudoConectar) {
            return false;
        }
    }
}
