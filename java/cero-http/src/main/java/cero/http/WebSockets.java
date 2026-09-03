package cero.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

/**
 * Apretón de manos y bucle de marcos de WebSocket (RFC 6455) sobre una conexión de cero-http.
 *
 * <pre>
 *   Cero.app().routes(r -&gt; r.get("/chat", contexto -&gt;
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
     *
     * <p>Solo admite el mismo origen: un WebSocket lleva las cookies del sitio y la política del
     * mismo origen no lo protege. Guía: https://cero.ginit.dev/guia#tiempo-real
     */
    public static void accept(Request request, Response response, WebSocketHandler handler) {
        accept(request, response, handler, Set.of());
    }

    /** Igual, admitiendo además estos orígenes exactos: {@code "https://otra.ginit.dev"}. */
    public static void accept(Request request, Response response, WebSocketHandler handler,
                              Set<String> origenesAdmitidos) {
        if (!isUpgrade(request)) {
            throw new HttpException(400, "no es una petición de cambio a WebSocket");
        }
        String origen = request.header("Origin");
        if (!origenAdmitido(request, origen, origenesAdmitidos)) {
            // Se nombran los dos valores: sin ellos, un proxy que reescribe Host se depura a ciegas.
            throw new HttpException(403, "origen no admitido para WebSocket: " + origen
                    + " (Host: " + request.header("Host") + ")");
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
                // 6455 §5.2: la longitud va en su forma más corta, o hay dos marcos para un mensaje.
                if (longitud < 126) {
                    throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO,
                            "longitud de 16 bits para un valor que cabe en 7");
                }
            } else if (longitud == 127) {
                longitud = 0;
                for (int i = 0; i < 8; i++) {
                    longitud = (longitud << 8) | leer(entrada);
                }
                if (longitud < 0) {
                    throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO, "longitud negativa");
                }
                if (longitud <= 0xFFFF) {
                    throw new CierreProtocolo(WebSocket.CIERRE_PROTOCOLO,
                            "longitud de 64 bits para un valor que cabe en 16");
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

    private static void entregar(WebSocket socket, WebSocketHandler handler, int codigo, byte[] carga)
            throws CierreProtocolo {
        if (codigo != WebSocket.TEXTO) {
            handler.onBinary(socket, carga);
            return;
        }
        // 6455 §8.1: texto mal formado se cierra con 1007. `new String` lo sustituiría en silencio.
        handler.onMessage(socket, texto(carga));
    }

    /** El texto del marco, o cierre 1007 si los bytes no son UTF-8 válido. */
    private static String texto(byte[] carga) throws CierreProtocolo {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(carga))
                    .toString();
        } catch (CharacterCodingException roto) {
            throw new CierreProtocolo(WebSocket.CIERRE_DATOS_INVALIDOS,
                    "el marco de texto no es UTF-8 válido");
        }
    }

    /** Mismo origen, o uno declarado. Sin {@code Origin} pasa: no hay navegador de por medio. */
    static boolean origenAdmitido(Request request, String origen, Set<String> admitidos) {
        if (origen == null || origen.isBlank()) {
            return true;
        }
        String limpio = origen.trim();
        if (admitidos.contains(limpio)) {
            return true;
        }
        String host = request.header("Host");
        if (host == null || host.isBlank()) {
            return false;
        }
        int barras = limpio.indexOf("//");
        if (barras < 0) {
            // "null" —iframe con sandbox, documento local— no es un origen.
            return false;
        }
        return limpio.substring(barras + 2).equalsIgnoreCase(host.trim());
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
