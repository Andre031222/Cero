package lux.http;

/** Qué hacer con una conexión WebSocket. Todos los métodos tienen una implementación vacía. */
public interface WebSocketHandler {

    default void onOpen(WebSocket socket) {
    }

    default void onMessage(WebSocket socket, String texto) {
    }

    default void onBinary(WebSocket socket, byte[] datos) {
    }

    /** Llega una vez, siempre, tanto si cerró el cliente como si cerró el servidor. */
    default void onClose(WebSocket socket, int codigo, String motivo) {
    }

    default void onError(WebSocket socket, Throwable fallo) {
    }
}
