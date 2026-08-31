package corvo.core;

import corvo.http.WebSocket;
import corvo.http.WebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trozos de página que el servidor vuelve a pintar solo, sin escribir JavaScript.
 *
 * <p>La plantilla marca una zona y le pone nombre:
 *
 * <pre>
 *   &lt;div data-live="carrito"&gt;
 *     {{ include "carrito.html" }}
 *   &lt;/div&gt;
 *   &lt;script src="/corvo/live.js"&gt;&lt;/script&gt;
 * </pre>
 *
 * <p>Y desde cualquier punto del servidor:
 *
 * <pre>
 *   live.push("carrito", "carrito.html", modelo);
 * </pre>
 *
 * <p><b>Lo que viaja por el cable es HTML, no JSON.</b> Ese es el motivo de que esto exista: el
 * navegador no tiene que saber pintar nada, así que no hay una segunda copia de las plantillas en
 * JavaScript que se desincronice de las de verdad. La plantilla es una y vive en el servidor.
 *
 * <p>El cliente son unas cuarenta líneas que sirve el propio framework en {@code /corvo/live.js}.
 * No hay paso de compilación, ni {@code node_modules}, ni nada que instalar.
 */
public final class Live {

    /** Suscriptores por zona. Un socket puede estar en varias. */
    private final Map<String, Set<WebSocket>> porZona = new ConcurrentHashMap<>();

    private ViewRenderer views;

    private Live() {
    }

    public static Live enabled() {
        return new Live();
    }

    void views(ViewRenderer value) {
        views = value;
    }

    /** Cuántos navegadores escuchan una zona. Para pruebas y para decidir si merece pintar. */
    public int listeners(String zona) {
        Set<WebSocket> abiertos = porZona.get(zona);
        return abiertos == null ? 0 : abiertos.size();
    }

    /**
     * Rinde la plantilla y manda el resultado a quien esté mirando esa zona.
     *
     * <p>Si no hay nadie escuchando, <b>no se rinde</b>. Pintar una plantilla para descubrir
     * después que no había destinatarios es trabajo tirado, y en una tarea periódica que empuja
     * cada segundo se nota.
     */
    public void push(String zona, String plantilla, Object modelo) {
        Set<WebSocket> abiertos = porZona.get(zona);
        if (abiertos == null || abiertos.isEmpty()) {
            return;
        }
        String html;
        try {
            html = views.render(plantilla, modelo);
        } catch (Exception fallo) {
            throw new IllegalStateException("no se pudo pintar la zona '" + zona + "'", fallo);
        }
        enviar(zona, abiertos, html);
    }

    /** Manda HTML ya hecho, para quien lo construya por su cuenta. */
    public void pushHtml(String zona, String html) {
        Set<WebSocket> abiertos = porZona.get(zona);
        if (abiertos != null && !abiertos.isEmpty()) {
            enviar(zona, abiertos, html);
        }
    }

    private void enviar(String zona, Set<WebSocket> abiertos, String html) {
        String marco = Json.write(Map.of("zona", zona, "html", html));
        for (WebSocket socket : abiertos) {
            try {
                socket.send(marco);
            } catch (RuntimeException fallo) {
                // Un socket que se cayó entre el listeners() y el send() no puede tumbar el
                // envío a los demás. Se descarta y sigue: onClose lo quitará igualmente, pero
                // puede llegar tarde.
                abiertos.remove(socket);
            }
        }
    }

    /** El manejador que atiende {@code /corvo/live}. Lo registra {@code Corvo.live(...)}. */
    WebSocketHandler handler() {
        return new WebSocketHandler() {
            @Override
            public void onMessage(WebSocket socket, String texto) {
                // El único mensaje que el cliente manda es la lista de zonas que tiene en la
                // página. No se acepta nada más: este canal empuja, no recibe órdenes.
                for (String zona : texto.split(",")) {
                    String limpia = zona.trim();
                    if (!limpia.isEmpty() && limpia.length() <= 64) {
                        porZona.computeIfAbsent(limpia, k -> ConcurrentHashMap.newKeySet()).add(socket);
                    }
                }
            }

            @Override
            public void onClose(WebSocket socket, int codigo, String motivo) {
                quitar(socket);
            }

            @Override
            public void onError(WebSocket socket, Throwable fallo) {
                quitar(socket);
            }
        };
    }

    private void quitar(WebSocket socket) {
        for (Set<WebSocket> abiertos : porZona.values()) {
            abiertos.remove(socket);
        }
    }

    /**
     * El cliente. Va aquí dentro y no en un archivo suelto para que no exista la posibilidad de
     * desplegar el framework sin él.
     */
    static final String GUION = """
            (() => {
              const zonas = () => [...document.querySelectorAll('[data-live]')]
                .map(e => e.getAttribute('data-live'));

              let socket, espera = 500;

              const conectar = () => {
                const url = (location.protocol === 'https:' ? 'wss://' : 'ws://')
                          + location.host + '/corvo/live';
                socket = new WebSocket(url);

                socket.onopen = () => {
                  espera = 500;
                  socket.send(zonas().join(','));
                };

                socket.onmessage = (evento) => {
                  const { zona, html } = JSON.parse(evento.data);
                  const destino = document.querySelector(`[data-live="${zona}"]`);
                  if (destino) destino.innerHTML = html;
                };

                // Reconectar con espera creciente. Una conexión viva que se muere en silencio
                // es peor que no tenerla: la página se queda enseñando datos viejos sin decirlo.
                socket.onclose = () => {
                  setTimeout(conectar, espera);
                  espera = Math.min(espera * 2, 15000);
                };
              };

              conectar();
            })();
            """;
}
