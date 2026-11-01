package cero.http;

public interface WebSocketHandler {

    default void onOpen(WebSocket socket) {
    }

    default void onMessage(WebSocket socket, String texto) {
    }

    default void onBinary(WebSocket socket, byte[] datos) {
    }

    default void onClose(WebSocket socket, int codigo, String motivo) {
    }

    default void onError(WebSocket socket, Throwable fallo) {
    }
}
