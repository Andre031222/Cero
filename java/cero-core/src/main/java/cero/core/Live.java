package cero.core;

import cero.http.Request;
import cero.http.WebSocket;
import cero.http.WebSocketHandler;

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
 *   &lt;script src="/cero/live.js"&gt;&lt;/script&gt;
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
 * <p>El cliente son unas cuarenta líneas que sirve el propio framework en {@code /cero/live.js}.
 * No hay paso de compilación, ni {@code node_modules}, ni nada que instalar.
 *
 * <p>Una zona es un canal de difusión: si lleva datos de una sola persona, hace falta
 * {@link #autorizar}. Guía: https://cero.ginit.dev/guia#live
 */
public final class Live {

    /** Decide si una conexión puede escuchar una zona. */
    @FunctionalInterface
    public interface Guardia {

        /** {@code true} si esta petición puede suscribirse a esa zona. */
        boolean permite(Request peticion, String zona);
    }

    /** Suscriptores por zona. Un socket puede estar en varias. */
    private final Map<String, Set<WebSocket>> porZona = new ConcurrentHashMap<>();

    /** Dónde se anotan las zonas de cada socket, dentro de sus propios atributos. */
    private static final String ATRIBUTO_ZONAS = "cero.live.zonas";

    private static final int MAX_ZONAS_POR_SOCKET = 32;
    private static final int MAX_LARGO_ZONA = 64;

    private static final Log log = Log.of(Live.class);

    private ViewRenderer views;

    /** Por defecto toda zona es pública: es lo que quiere una zona de contenido común. */
    private Guardia guardia = (peticion, zona) -> true;

    /** Orígenes admitidos además del propio. Vacío = solo el mismo origen. */
    private Set<String> origenes = Set.of();

    private Live() {
    }

    public static Live enabled() {
        return new Live();
    }

    /** Orígenes admitidos además del propio. Sin esto, solo el mismo origen. */
    public Live origenes(String... valores) {
        origenes = valores == null ? Set.of() : Set.of(valores);
        return this;
    }

    Set<String> origenes() {
        return origenes;
    }

    /** Quién puede escuchar qué. Una zona rechazada no se da de alta; el socket sigue abierto. */
    public Live autorizar(Guardia value) {
        guardia = value == null ? (peticion, zona) -> true : value;
        return this;
    }

    void views(ViewRenderer value) {
        views = value;
    }

    /** Cuántos navegadores escuchan una zona. Para pruebas y para decidir si merece pintar. */
    public int listeners(String zona) {
        Set<WebSocket> abiertos = porZona.get(zona);
        return abiertos == null ? 0 : abiertos.size();
    }

    /** Cuántas zonas hay en seguimiento. Vuelve a cero cuando se van todos. */
    public int zonas() {
        return porZona.size();
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
                desapuntar(zona, socket);
            }
        }
    }

    /** El manejador que atiende {@code /cero/live}. Lo registra {@code Cero.live(...)}. */
    WebSocketHandler handler() {
        return new WebSocketHandler() {
            @Override
            public void onMessage(WebSocket socket, String texto) {
                // El único mensaje que el cliente manda es la lista de zonas que tiene en la
                // página. No se acepta nada más: este canal empuja, no recibe órdenes.
                for (String zona : texto.split(",")) {
                    // Alcanzado el tope de la conexión, el resto del mensaje se descarta.
                    if (!apuntar(socket, zona.trim())) {
                        break;
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

    /** Da de alta una zona para este socket. {@code false} si no caben más. */
    private boolean apuntar(WebSocket socket, String zona) {
        if (zona.isEmpty() || zona.length() > MAX_LARGO_ZONA) {
            return true;
        }
        Set<String> mias = zonasDe(socket);
        if (mias.contains(zona)) {
            return true;
        }
        if (mias.size() >= MAX_ZONAS_POR_SOCKET) {
            log.warn("live: la conexión pidió más de {} zonas; se ignoran las demás",
                    MAX_ZONAS_POR_SOCKET);
            return false;
        }
        if (!guardia.permite(socket.request(), zona)) {
            return true;
        }
        mias.add(zona);
        porZona.computeIfAbsent(zona, k -> ConcurrentHashMap.newKeySet()).add(socket);
        return true;
    }

    /** Las zonas de este socket. Van en sus atributos para que se vayan con la conexión. */
    @SuppressWarnings("unchecked")
    private Set<String> zonasDe(WebSocket socket) {
        return (Set<String>) socket.attributes()
                .computeIfAbsent(ATRIBUTO_ZONAS, k -> ConcurrentHashMap.newKeySet());
    }

    private void quitar(WebSocket socket) {
        for (String zona : zonasDe(socket)) {
            desapuntar(zona, socket);
        }
        socket.attributes().remove(ATRIBUTO_ZONAS);
    }

    /** Saca el socket de la zona y, si era el último, saca la zona del mapa. */
    private void desapuntar(String zona, WebSocket socket) {
        porZona.computeIfPresent(zona, (nombre, abiertos) -> {
            abiertos.remove(socket);
            return abiertos.isEmpty() ? null : abiertos;
        });
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
                          + location.host + '/cero/live';
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
