package cero.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Una conexión WebSocket abierta, tal como la ve la aplicación.
 *
 * <p>Enviar es seguro desde cualquier hilo; los envíos se serializan sobre el flujo de salida.
 * Leer lo hace el hilo que atiende la conexión, que llama al {@link WebSocketHandler}.
 */
public final class WebSocket {

    static final int TEXTO = 1;
    static final int BINARIO = 2;
    static final int CIERRE = 8;
    static final int PING = 9;
    static final int PONG = 10;

    public static final int CIERRE_NORMAL = 1000;
    public static final int CIERRE_PROTOCOLO = 1002;
    public static final int CIERRE_DATOS_INVALIDOS = 1007;
    public static final int CIERRE_DEMASIADO_GRANDE = 1009;

    private final OutputStream salida;
    private final Request peticion;
    private final Map<String, Object> atributos = new ConcurrentHashMap<>();
    private final Object cerrojoEscritura = new Object();

    private volatile boolean abierto = true;
    private volatile boolean cierreEnviado;

    WebSocket(OutputStream salida, Request peticion) {
        this.salida = salida;
        this.peticion = peticion;
    }

    public Request request() {
        return peticion;
    }

    public boolean isOpen() {
        return abierto;
    }

    /** Espacio por conexión para que la aplicación cuelgue lo suyo (usuario, sala, lo que sea). */
    public Map<String, Object> attributes() {
        return atributos;
    }

    public void send(String texto) {
        escribir(TEXTO, texto.getBytes(StandardCharsets.UTF_8));
    }

    public void send(byte[] datos) {
        escribir(BINARIO, datos);
    }

    public void ping(byte[] datos) {
        escribir(PING, datos);
    }

    public void close() {
        close(CIERRE_NORMAL, "");
    }

    public void close(int codigo, String motivo) {
        if (cierreEnviado) {
            return;
        }
        cierreEnviado = true;
        byte[] razon = motivo == null ? new byte[0] : motivo.getBytes(StandardCharsets.UTF_8);
        byte[] carga = new byte[2 + razon.length];
        carga[0] = (byte) (codigo >> 8);
        carga[1] = (byte) codigo;
        System.arraycopy(razon, 0, carga, 2, razon.length);
        try {
            escribirMarco(CIERRE, carga);
        } catch (IOException ignorado) {
            // El otro extremo ya se fue; el cierre local pasa igual.
        }
        abierto = false;
    }

    void pong(byte[] datos) {
        escribir(PONG, datos);
    }

    void marcarCerrado() {
        abierto = false;
    }

    private void escribir(int codigo, byte[] carga) {
        if (!abierto) {
            throw new IllegalStateException("la conexión WebSocket ya está cerrada");
        }
        try {
            escribirMarco(codigo, carga);
        } catch (IOException fallo) {
            abierto = false;
            throw new UncheckedIoException(fallo);
        }
    }

    private void escribirMarco(int codigo, byte[] carga) throws IOException {
        synchronized (cerrojoEscritura) {
            salida.write(0x80 | codigo);
            int longitud = carga.length;
            if (longitud < 126) {
                salida.write(longitud);
            } else if (longitud <= 0xFFFF) {
                salida.write(126);
                salida.write(longitud >> 8);
                salida.write(longitud);
            } else {
                salida.write(127);
                for (int desplazamiento = 56; desplazamiento >= 0; desplazamiento -= 8) {
                    salida.write((int) ((long) longitud >> desplazamiento));
                }
            }
            salida.write(carga);
            salida.flush();
        }
    }

    /** Un fallo de red al enviar, sin obligar a la aplicación a declarar {@code IOException}. */
    public static final class UncheckedIoException extends RuntimeException {
        UncheckedIoException(IOException causa) {
            super(causa);
        }
    }
}
