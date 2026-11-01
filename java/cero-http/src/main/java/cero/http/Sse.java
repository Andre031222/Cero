package cero.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class Sse implements AutoCloseable {

    private final OutputStream salida;
    private volatile boolean abierto = true;

    private Sse(OutputStream salida) {
        this.salida = salida;
    }

    public static Sse open(Response response) {
        response.header("Content-Type", "text/event-stream; charset=utf-8");
        response.header("Cache-Control", "no-cache");
        response.header("X-Accel-Buffering", "no");
        return new Sse(response.stream());
    }

    public void send(String datos) {
        enviar(null, datos, null);
    }

    public void send(String evento, String datos) {
        enviar(evento, datos, null);
    }

    public void send(String evento, String datos, String id) {
        enviar(evento, datos, id);
    }

    public void latido() {
        escribir(": latido\n\n");
    }

    public void reintentarEn(long millis) {
        escribir("retry: " + millis + "\n\n");
    }

    public boolean abierto() {
        return abierto;
    }

    @Override
    public void close() {
        if (!abierto) {
            return;
        }
        abierto = false;
        try {
            salida.close();
        } catch (IOException cerrado) {
        }
    }

    private void enviar(String evento, String datos, String id) {
        StringBuilder trama = new StringBuilder();
        if (id != null) {
            trama.append("id: ").append(id).append('\n');
        }
        if (evento != null) {
            trama.append("event: ").append(evento).append('\n');
        }
        for (String linea : datos.split("\n", -1)) {
            trama.append("data: ").append(linea).append('\n');
        }
        trama.append('\n');
        escribir(trama.toString());
    }

    private void escribir(String trama) {
        if (!abierto) {
            return;
        }
        try {
            salida.write(trama.getBytes(StandardCharsets.UTF_8));
            salida.flush();
        } catch (IOException seFue) {
            abierto = false;
        }
    }
}
