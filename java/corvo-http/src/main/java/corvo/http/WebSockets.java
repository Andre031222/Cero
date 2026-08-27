package corvo.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Apretón de manos y bucle de marcos de WebSocket (RFC 6455) sobre una conexión de corvo-http.
 *
 * <pre>
 *   Corvo.app().routes(r -&gt; r.get("/chat", contexto -&gt;
 *       WebSockets.accept(contexto.request(), contexto.response(), new WebSocketHandler() {
 *           public void onMessage(WebSocket socket, String texto) {
 *               socket.send("recibido: " + texto);
 *           }
 *       })));
 * </pre>
 *
 * <p>El hilo que atiende la petición se queda dentro del bucle hasta que la conexión se cierra.
 * Con un hilo virtual por conexión eso no cuesta un hilo del sistema, que es justo lo que hacía
 * caro este modelo en un contenedor clásico.
 */
public final class WebSockets {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** Techo por mensaje, contando fragmentos. Evita que un cliente reserve memoria a voluntad. */
    private static final long MAXIMO_MENSAJE = 1L << 20;

    private WebSockets() {
    }

    public static boolean isUpgrade(Request request) {
        return contieneToken(request.header("Connection"), "upgrade")
                && "websocket".equalsIgnoreCase(valor(request.header("Upgrade")));
    }

    /**
     * Completa el apretón de manos y atiende la conexión hasta que se cierre. Si la petición no
     * es una petición de cambio válida responde 400 y devuelve sin abrir nada.
     */
    public static void accept(Request request, Response response, WebSocketHandler handler) {
        if (!isUpgrade(request)) {
            throw new HttpException(400, "no es una petición de cambio a WebSocket");
        }
        String clave = request.header("Sec-WebSocket-Key");
        if (clave == null || clave.isBlank()) {
            throw new HttpException(400, "falta Sec-WebSocket-Key");
        }
        String version = request.header("Sec-WebSocket-Version");
        if (!"13".equals(valor(version))) {
            response.header("Sec-WebSocket-Version", "13");
            throw new HttpException(426, "se requiere la versión 13 de WebSocket");
        }

        response.header("Upgrade", "websocket");
        response.header("Connection", "Upgrade");
        response.header("Sec-WebSocket-Accept", aceptacion(clave));

        Duplex canal = response.switchProtocols();
        WebSocket socket = new WebSocket(canal.out(), request);

        int codigo = WebSocket.CIERRE_NORMAL;
        String motivo = "";
        try {
            handler.onOpen(socket);
            String cierre = bucle(canal.in(), socket, handler);
            motivo = cierre == null ? "" : cierre;
        } catch (CierreProtocolo roto) {
            codigo = roto.codigo;
            motivo = roto.getMessage();
            socket.close(codigo, motivo);
        } catch (EOFException cortado) {
            codigo = 1006;
            motivo = "la conexión se cortó";
        } catch (IOException | RuntimeException fallo) {
            codigo = 1011;
            motivo = "error del servidor";
            handler.onError(socket, fallo);
        } finally {
            socket.close(codigo, motivo);
            socket.marcarCerrado();
            handler.onClose(socket, codigo, motivo);
        }
    }

    /** Lee marcos hasta el cierre. Devuelve el motivo que mandó el cliente, si mandó alguno. */
    private static String bucle(InputStream entrada, WebSocket socket, WebSocketHandler handler)
            throws IOException {

        ByteArrayOutputStream acumulado = new ByteArrayOutputStream();
        int codigoAcumulado = 0;

        while (true) {
            int primero = entrada.read();
            if (primero < 0) {
                throw new EOFException();
            }
            int segundo = leer(entrada);

            boolean fin = (primero & 0x80) != 0;
            if ((primero & 0x70) != 0) {
                throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO, "bits reservados en uso");
            }
            int codigo = primero & 0x0F;
            boolean enmascarado = (segundo & 0x80) != 0;
            long longitud = segundo & 0x7F;

            if (!enmascarado) {
                throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO, "el cliente debe enmascarar");
            }
            if (longitud == 126) {
                longitud = (leer(entrada) << 8) | leer(entrada);
            } else if (longitud == 127) {
                longitud = 0;
                for (int i = 0; i < 8; i++) {
                    longitud = (longitud << 8) | leer(entrada);
                }
                if (longitud < 0) {
                    throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO, "longitud negativa");
                }
            }

            boolean control = (codigo & 0x08) != 0;
            if (control && (longitud > 125 || !fin)) {
                throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO,
                        "un marco de control no se fragmenta ni pasa de 125 bytes");
            }
            if (longitud > MAXIMO_MENSAJE || acumulado.size() + longitud > MAXIMO_MENSAJE) {
                throw new CierreProtocolo(WebSocket.CIERRE_DEMASIADO_GRANDE, "mensaje demasiado grande");
            }

            byte[] mascara = new byte[4];
            leerDelTodo(entrada, mascara);
            byte[] carga = new byte[(int) longitud];
            leerDelTodo(entrada, carga);
            for (int i = 0; i < carga.length; i++) {
                carga[i] ^= mascara[i & 3];
            }

            switch (codigo) {
                case WebSocket.PING -> socket.pong(carga);
                case WebSocket.PONG -> {
                }
                case WebSocket.CIERRE -> {
                    if (carga.length == 1) {
                        throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO, "cierre con un solo byte");
                    }
                    return carga.length >= 2
                            ? new String(carga, 2, carga.length - 2, StandardCharsets.UTF_8)
                            : "";
                }
                case WebSocket.TEXTO, WebSocket.BINARIO -> {
                    if (codigoAcumulado != 0) {
                        throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO,
                                "llegó un mensaje nuevo con otro a medias");
                    }
                    if (fin) {
                        entregar(socket, handler, codigo, carga);
                    } else {
                        codigoAcumulado = codigo;
                        acumulado.write(carga);
                    }
                }
                case 0 -> {
                    if (codigoAcumulado == 0) {
                        throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO,
                                "continuación sin mensaje que continuar");
                    }
                    acumulado.write(carga);
                    if (fin) {
                        entregar(socket, handler, codigoAcumulado, acumulado.toByteArray());
                        acumulado.reset();
                        codigoAcumulado = 0;
                    }
                }
                default -> throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO,
                        "código de operación desconocido: " + codigo);
            }
        }
    }

    private static void entregar(WebSocket socket, WebSocketHandler handler, int codigo, byte[] carga) {
        if (codigo == WebSocket.TEXTO) {
            handler.onMessage(socket, new String(carga, StandardCharsets.UTF_8));
        } else {
            handler.onBinary(socket, carga);
        }
    }

    static String aceptacion(String clave) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] resumen = sha1.digest((clave.trim() + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(resumen);
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException("el JDK no trae SHA-1", imposible);
        }
    }

    private static int leer(InputStream entrada) throws IOException {
        int byteLeido = entrada.read();
        if (byteLeido < 0) {
            throw new EOFException();
        }
        return byteLeido;
    }

    private static void leerDelTodo(InputStream entrada, byte[] destino) throws IOException {
        int leidos = 0;
        while (leidos < destino.length) {
            int ahora = entrada.read(destino, leidos, destino.length - leidos);
            if (ahora < 0) {
                throw new EOFException();
            }
            leidos += ahora;
        }
    }

    private static boolean contieneToken(String cabecera, String token) {
        if (cabecera == null) {
            return false;
        }
        for (String parte : cabecera.split(",")) {
            if (parte.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private static String valor(String cabecera) {
        return cabecera == null ? null : cabecera.trim();
    }

    private static final class CierreProtocolo extends IOException {

        final int codigo;

        CierreProtocolo(int codigo, String motivo) {
            super(motivo);
            this.codigo = codigo;
        }
    }
}
