package lux.core;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * Cliente SMTP sobre {@link Socket}, sin dependencias.
 *
 * <pre>{@code
 * Mail correo = Mail.smtp("smtp.gmail.com", 587)
 *                   .credentials(usuario, clave)
 *                   .from("Portal FINESI <no-responder@unap.edu.pe>");
 *
 * correo.send(Mail.Mensaje.texto("alguien@unap.edu.pe", "Recibido", "Gracias por escribir."));
 * }</pre>
 *
 * <p>JavaMail no forma parte de Java SE, así que mandar un correo obligaba a traerse una
 * dependencia — justo lo que este proyecto evita. SMTP es un protocolo de texto y cabe aquí.
 *
 * <p>Habla STARTTLS por defecto: se conecta en claro al 587 y sube a TLS antes de autenticarse.
 * Para el 465, que es TLS desde el primer byte, {@link #ssl(boolean)}.
 */
public final class Mail {

    private static final Log log = Log.of(Mail.class);
    private static final DateTimeFormatter FECHA =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", java.util.Locale.US);

    private final String host;
    private final int puerto;
    private String usuario;
    private String clave;
    private String remitente = "no-responder@localhost";
    private boolean startTls = true;
    private boolean ssl;
    private int timeoutMillis = 15_000;

    private Mail(String host, int puerto) {
        this.host = host;
        this.puerto = puerto;
    }

    public static Mail smtp(String host, int puerto) {
        return new Mail(host, puerto);
    }

    public Mail credentials(String usuario, String clave) {
        this.usuario = usuario;
        this.clave = clave;
        return this;
    }

    public Mail from(String remitente) {
        this.remitente = remitente;
        return this;
    }

    public Mail startTls(boolean value) {
        this.startTls = value;
        return this;
    }

    /** TLS desde el primer byte, para el puerto 465. */
    public Mail ssl(boolean value) {
        this.ssl = value;
        return this;
    }

    public Mail timeout(Duration value) {
        this.timeoutMillis = (int) value.toMillis();
        return this;
    }

    public void send(Mensaje mensaje) {
        try (Sesion sesion = Sesion.abrir(this)) {
            sesion.saludar();
            sesion.autenticar(usuario, clave);
            sesion.enviar(direccionDe(remitente), mensaje, remitente);
        } catch (IOException fallo) {
            throw new MailException("no se pudo enviar el correo a " + mensaje.para(), fallo);
        }
    }

    /** Comprueba que el servidor responde y que las credenciales valen, sin mandar nada. */
    public boolean check() {
        try (Sesion sesion = Sesion.abrir(this)) {
            sesion.saludar();
            sesion.autenticar(usuario, clave);
            return true;
        } catch (IOException | MailException fallo) {
            log.error("el servidor de correo no responde o rechaza las credenciales: {}",
                    fallo.getMessage());
            return false;
        }
    }

    private static String direccionDe(String remitente) {
        int abre = remitente.indexOf('<');
        int cierra = remitente.indexOf('>');
        return abre >= 0 && cierra > abre ? remitente.substring(abre + 1, cierra) : remitente;
    }

    public record Mensaje(String para, String asunto, String cuerpo, boolean html) {

        public static Mensaje texto(String para, String asunto, String cuerpo) {
            return new Mensaje(para, asunto, cuerpo, false);
        }

        public static Mensaje html(String para, String asunto, String cuerpo) {
            return new Mensaje(para, asunto, cuerpo, true);
        }
    }

    public static final class MailException extends RuntimeException {

        MailException(String mensaje) {
            super(mensaje);
        }

        MailException(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    private static final class Sesion implements AutoCloseable {

        private final Mail config;
        private Socket socket;
        private BufferedReader entrada;
        private OutputStream salida;

        private Sesion(Mail config) {
            this.config = config;
        }

        static Sesion abrir(Mail config) throws IOException {
            Sesion sesion = new Sesion(config);
            Socket bruto = config.ssl
                    ? SSLSocketFactory.getDefault().createSocket()
                    : new Socket();
            bruto.connect(new InetSocketAddress(config.host, config.puerto), config.timeoutMillis);
            bruto.setSoTimeout(config.timeoutMillis);
            sesion.usar(bruto);
            sesion.esperar(220);
            return sesion;
        }

        private void usar(Socket nuevo) throws IOException {
            socket = nuevo;
            entrada = new BufferedReader(
                    new InputStreamReader(nuevo.getInputStream(), StandardCharsets.UTF_8));
            salida = nuevo.getOutputStream();
        }

        void saludar() throws IOException {
            List<String> capacidades = decir("EHLO " + local(), 250);
            if (!config.ssl && config.startTls && anuncia(capacidades, "STARTTLS")) {
                decir("STARTTLS", 220);
                SSLSocketFactory fabrica = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket seguro = (SSLSocket) fabrica.createSocket(
                        socket, config.host, config.puerto, true);
                seguro.startHandshake();
                usar(seguro);
                decir("EHLO " + local(), 250);
            }
        }

        void autenticar(String usuario, String clave) throws IOException {
            if (usuario == null || clave == null) {
                return;
            }
            decir("AUTH LOGIN", 334);
            decir(base64(usuario), 334);
            decir(base64(clave), 235);
        }

        void enviar(String desde, Mensaje mensaje, String cabeceraDe) throws IOException {
            decir("MAIL FROM:<" + desde + ">", 250);
            decir("RCPT TO:<" + mensaje.para() + ">", 250);
            decir("DATA", 354);
            escribir(cuerpoCompleto(cabeceraDe, mensaje));
            esperar(250);
            decir("QUIT", 221);
        }

        private String cuerpoCompleto(String de, Mensaje mensaje) {
            StringBuilder trama = new StringBuilder();
            trama.append("From: ").append(de).append("\r\n");
            trama.append("To: ").append(mensaje.para()).append("\r\n");
            trama.append("Subject: ").append(asuntoCodificado(mensaje.asunto())).append("\r\n");
            trama.append("Date: ").append(ZonedDateTime.now().format(FECHA)).append("\r\n");
            trama.append("MIME-Version: 1.0\r\n");
            trama.append("Content-Type: ")
                    .append(mensaje.html() ? "text/html" : "text/plain")
                    .append("; charset=UTF-8\r\n");
            trama.append("Content-Transfer-Encoding: 8bit\r\n\r\n");

            for (String linea : mensaje.cuerpo().split("\n", -1)) {
                String limpia = linea.endsWith("\r") ? linea.substring(0, linea.length() - 1) : linea;
                // Una línea que empieza por punto marcaría el final del mensaje; se dobla.
                if (limpia.startsWith(".")) {
                    trama.append('.');
                }
                trama.append(limpia).append("\r\n");
            }
            trama.append(".\r\n");
            return trama.toString();
        }

        /** RFC 2047: un asunto con tildes viaja codificado o llega roto. */
        private static String asuntoCodificado(String asunto) {
            if (asunto.chars().allMatch(c -> c >= 32 && c < 127)) {
                return asunto;
            }
            return "=?UTF-8?B?" + base64(asunto) + "?=";
        }

        private List<String> decir(String orden, int esperado) throws IOException {
            escribir(orden + "\r\n");
            return esperar(esperado);
        }

        private void escribir(String texto) throws IOException {
            salida.write(texto.getBytes(StandardCharsets.UTF_8));
            salida.flush();
        }

        private List<String> esperar(int esperado) throws IOException {
            List<String> lineas = new java.util.ArrayList<>();
            String linea;
            while ((linea = entrada.readLine()) != null) {
                lineas.add(linea);
                if (linea.length() < 4 || linea.charAt(3) != '-') {
                    break;
                }
            }
            if (lineas.isEmpty()) {
                throw new MailException("el servidor cerró la conexión sin responder");
            }
            String ultima = lineas.get(lineas.size() - 1);
            int codigo = codigoDe(ultima);
            if (codigo != esperado) {
                throw new MailException("el servidor respondió " + ultima + ", se esperaba "
                        + esperado);
            }
            return lineas;
        }

        private static int codigoDe(String linea) {
            try {
                return Integer.parseInt(linea.substring(0, 3));
            } catch (RuntimeException mal) {
                throw new MailException("respuesta SMTP ilegible: " + linea);
            }
        }

        private static boolean anuncia(List<String> capacidades, String cual) {
            return capacidades.stream().anyMatch(l -> l.toUpperCase().contains(cual));
        }

        private static String base64(String texto) {
            return Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
        }

        private static String local() {
            try {
                return java.net.InetAddress.getLocalHost().getHostName();
            } catch (IOException sinNombre) {
                return "localhost";
            }
        }

        @Override
        public void close() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException yaCerrado) {
                // nada que hacer
            }
        }
    }
}
