package corvo.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** El cliente SMTP, contra un servidor de mentira que habla el protocolo de verdad. */
final class MailTests {

    private MailTests() {
    }

    static void run() throws Exception {
        Check.group("correo");

        conversacionCompleta();
        elPuntoSeDobla();
        asuntoConTildes();
        unRechazoSeNota();
    }

    private static void conversacionCompleta() throws Exception {
        try (Servidor servidor = Servidor.arrancar()) {
            Mail.smtp("127.0.0.1", servidor.puerto())
                    .startTls(false)
                    .credentials("andre", "secreta")
                    .from("Portal <no-responder@unap.edu.pe>")
                    .send(Mail.Mensaje.texto("alguien@unap.edu.pe", "Recibido", "Gracias."));

            List<String> dicho = servidor.recibido();
            Check.that("saluda con EHLO", dicho.stream().anyMatch(l -> l.startsWith("EHLO")));
            Check.that("se autentica", dicho.contains("AUTH LOGIN"));
            Check.that("manda el usuario en base64", dicho.contains("YW5kcmU="));
            Check.that("y la clave", dicho.contains("c2VjcmV0YQ=="));
            Check.that("usa la dirección, no el nombre visible",
                    dicho.contains("MAIL FROM:<no-responder@unap.edu.pe>"));
            Check.that("y el destinatario", dicho.contains("RCPT TO:<alguien@unap.edu.pe>"));
            Check.that("se despide", dicho.contains("QUIT"));

            String cuerpo = servidor.mensaje();
            Check.that("el From lleva el nombre visible",
                    cuerpo.contains("From: Portal <no-responder@unap.edu.pe>"));
            Check.that("declara UTF-8", cuerpo.contains("charset=UTF-8"));
            Check.that("y va el texto", cuerpo.contains("Gracias."));
        }
    }

    /** Una línea que empieza por punto marcaría el final del mensaje: hay que doblarla. */
    private static void elPuntoSeDobla() throws Exception {
        try (Servidor servidor = Servidor.arrancar()) {
            Mail.smtp("127.0.0.1", servidor.puerto()).startTls(false)
                    .send(Mail.Mensaje.texto("a@b.pe", "Lista", "uno\n.dos\ntres"));

            String cuerpo = servidor.mensaje();
            Check.that("la línea que empieza por punto se dobla", cuerpo.contains("..dos"));
            Check.that("y el mensaje no se cortó ahí", cuerpo.contains("tres"));
        }
    }

    private static void asuntoConTildes() throws Exception {
        try (Servidor servidor = Servidor.arrancar()) {
            Mail.smtp("127.0.0.1", servidor.puerto()).startTls(false)
                    .send(Mail.Mensaje.texto("a@b.pe", "Inscripción abierta", "hola"));

            String cuerpo = servidor.mensaje();
            Check.that("el asunto con tildes va codificado", cuerpo.contains("Subject: =?UTF-8?B?"));
            Check.that("y no en crudo", !cuerpo.contains("Subject: Inscripción"));
        }
    }

    private static void unRechazoSeNota() throws Exception {
        try (Servidor servidor = Servidor.arrancar(true)) {
            String mensaje = "";
            try {
                Mail.smtp("127.0.0.1", servidor.puerto()).startTls(false)
                        .send(Mail.Mensaje.texto("a@b.pe", "x", "y"));
            } catch (RuntimeException esperado) {
                mensaje = String.valueOf(esperado.getCause() == null
                        ? esperado.getMessage() : esperado.getCause().getMessage());
            }
            Check.that("un rechazo del servidor no pasa desapercibido",
                    mensaje.contains("550") || mensaje.contains("no se pudo enviar"));
        }
    }

    private static final class Servidor implements AutoCloseable {

        private final ServerSocket puerta;
        private final List<String> dicho = new ArrayList<>();
        private final StringBuilder mensaje = new StringBuilder();
        private final CountDownLatch terminado = new CountDownLatch(1);
        private final boolean rechaza;

        private Servidor(ServerSocket puerta, boolean rechaza) {
            this.puerta = puerta;
            this.rechaza = rechaza;
        }

        static Servidor arrancar() throws IOException {
            return arrancar(false);
        }

        static Servidor arrancar(boolean rechaza) throws IOException {
            Servidor servidor = new Servidor(new ServerSocket(0), rechaza);
            Thread.ofVirtual().start(servidor::atender);
            return servidor;
        }

        int puerto() {
            return puerta.getLocalPort();
        }

        List<String> recibido() throws InterruptedException {
            terminado.await(10, TimeUnit.SECONDS);
            return dicho;
        }

        String mensaje() throws InterruptedException {
            terminado.await(10, TimeUnit.SECONDS);
            return mensaje.toString();
        }

        private void atender() {
            try (Socket cliente = puerta.accept();
                 BufferedReader lector = new BufferedReader(
                         new InputStreamReader(cliente.getInputStream(), StandardCharsets.UTF_8))) {
                OutputStream salida = cliente.getOutputStream();
                responder(salida, "220 prueba lista");

                String linea;
                boolean enDatos = false;
                while ((linea = lector.readLine()) != null) {
                    if (enDatos) {
                        if (linea.equals(".")) {
                            enDatos = false;
                            responder(salida, rechaza ? "550 rechazado" : "250 aceptado");
                            continue;
                        }
                        mensaje.append(linea).append('\n');
                        continue;
                    }

                    dicho.add(linea);
                    String orden = linea.toUpperCase();
                    if (orden.startsWith("EHLO")) {
                        responder(salida, "250-prueba");
                        responder(salida, "250 AUTH LOGIN PLAIN");
                    } else if (orden.startsWith("AUTH")) {
                        responder(salida, "334 VXNlcm5hbWU6");
                    } else if (orden.startsWith("DATA")) {
                        enDatos = true;
                        responder(salida, "354 adelante");
                    } else if (orden.startsWith("QUIT")) {
                        responder(salida, "221 adiós");
                        break;
                    } else if (esperaAutenticacion(orden)) {
                        responder(salida, dicho.size() >= 4 ? "235 vale" : "334 UGFzc3dvcmQ6");
                    } else {
                        responder(salida, "250 vale");
                    }
                }
            } catch (IOException cortado) {
                // el cliente se fue; la prueba lo verá por lo que falte
            } finally {
                terminado.countDown();
            }
        }

        private boolean esperaAutenticacion(String orden) {
            return !orden.startsWith("MAIL") && !orden.startsWith("RCPT")
                    && !orden.startsWith("EHLO") && orden.matches("[A-Z0-9+/=]+");
        }

        private static void responder(OutputStream salida, String texto) throws IOException {
            salida.write((texto + "\r\n").getBytes(StandardCharsets.UTF_8));
            salida.flush();
        }

        @Override
        public void close() throws IOException {
            puerta.close();
        }
    }
}
