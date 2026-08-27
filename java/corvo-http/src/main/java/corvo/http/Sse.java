package corvo.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Eventos del servidor al navegador, por HTTP y en una sola dirección.
 *
 * <p>Es lo que sustituye a preguntar cada dos segundos: en vez de que el navegador insista, el
 * servidor manda cuando hay algo. Frente a un WebSocket, esto es HTTP normal —atraviesa proxies
 * sin configurar nada, se reconecta solo y no necesita otro protocolo—, y a cambio solo va del
 * servidor al cliente. Para un panel, un progreso o un aviso, es justo lo que hace falta.
 *
 * <pre>{@code
 * @Get("/eventos")
 * public void eventos(Context ctx) {
 *     try (Sse eventos = Sse.open(ctx.response())) {
 *         while (eventos.abierto()) {
 *             eventos.send(Json.write(metricas.snapshot()));
 *             Thread.sleep(1000);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>El hilo se queda dentro del bucle, y eso está bien: Corvo atiende cada conexión en su
 * propio hilo virtual, así que mil paneles abiertos son mil hilos virtuales, no mil del sistema.
 * Con hilos de plataforma esto no se podría hacer así — es la diferencia que trae Java 21.
 *
 * <p>En el navegador:
 * <pre>{@code new EventSource("/eventos").onmessage = e => pintar(JSON.parse(e.data));}</pre>
 */
public final class Sse implements AutoCloseable {

    private final OutputStream salida;
    private volatile boolean abierto = true;

    private Sse(OutputStream salida) {
        this.salida = salida;
    }

    /** Abre el flujo y escribe las cabeceras. A partir de aquí la respuesta ya está empezada. */
    public static Sse open(Response response) {
        response.header("Content-Type", "text/event-stream; charset=utf-8");
        response.header("Cache-Control", "no-cache");
        // Sin esto nginx guarda la respuesta en un búfer y los eventos llegan a ráfagas, o no
        // llegan: el proxy espera a tener bastante que mandar y aquí nunca hay "bastante".
        response.header("X-Accel-Buffering", "no");
        return new Sse(response.stream());
    }

    /** Manda un evento sin nombre — lo recoge {@code onmessage}. */
    public void send(String datos) {
        enviar(null, datos, null);
    }

    /** Manda un evento con nombre — lo recoge {@code addEventListener(nombre, …)}. */
    public void send(String evento, String datos) {
        enviar(evento, datos, null);
    }

    /**
     * Manda un evento con identificador. El navegador lo devuelve en {@code Last-Event-ID} al
     * reconectar, que es como se reanuda sin perder nada.
     */
    public void send(String evento, String datos, String id) {
        enviar(evento, datos, id);
    }

    /**
     * Un comentario, que el navegador ignora. Sirve para mantener viva la conexión cuando no hay
     * nada que contar: sin tráfico, un proxy o un cortafuegos la cierran por inactividad.
     */
    public void latido() {
        escribir(": latido\n\n");
    }

    /** Le dice al navegador cuánto esperar antes de reconectar. Por defecto son unos 3 s. */
    public void reintentarEn(long millis) {
        escribir("retry: " + millis + "\n\n");
    }

    /** {@code false} en cuanto el cliente se va: es la señal para salir del bucle. */
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
            // el cliente ya se había ido
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
        // Cada línea lleva su propio "data:". Sin esto, un JSON con saltos de línea rompe la
        // trama y el navegador recibe basura.
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
            // El cliente cerró la pestaña. No es un error: es el final normal de un flujo de
            // eventos, y quien esté en el bucle se entera por abierto().
            abierto = false;
        }
    }
}
