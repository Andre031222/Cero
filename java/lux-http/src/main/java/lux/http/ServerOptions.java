package lux.http;

public record ServerOptions(
        String host,
        int port,
        int backlog,
        int idleTimeoutMillis,
        int maxRequestLineBytes,
        int maxHeaderBytes,
        int maxHeaderCount,
        long maxBodyBytes,
        int readBufferBytes,
        int maxKeepAliveRequests) {

    public ServerOptions {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("puerto fuera de rango: " + port);
        }
        if (readBufferBytes < 512) {
            throw new IllegalArgumentException("buffer de lectura demasiado pequeño: " + readBufferBytes);
        }
        if (maxRequestLineBytes < 64 || maxHeaderBytes < 64) {
            throw new IllegalArgumentException("límites de cabecera demasiado pequeños");
        }
    }

    public static ServerOptions defaults() {
        return new ServerOptions("0.0.0.0", 8080, 1024, 30_000, 8_192, 32_768, 100, 10L << 20, 16_384, 1_000);
    }

    public ServerOptions host(String host) {
        return new ServerOptions(host, port, backlog, idleTimeoutMillis, maxRequestLineBytes,
                maxHeaderBytes, maxHeaderCount, maxBodyBytes, readBufferBytes, maxKeepAliveRequests);
    }

    public ServerOptions port(int port) {
        return new ServerOptions(host, port, backlog, idleTimeoutMillis, maxRequestLineBytes,
                maxHeaderBytes, maxHeaderCount, maxBodyBytes, readBufferBytes, maxKeepAliveRequests);
    }

    public ServerOptions idleTimeoutMillis(int millis) {
        return new ServerOptions(host, port, backlog, millis, maxRequestLineBytes,
                maxHeaderBytes, maxHeaderCount, maxBodyBytes, readBufferBytes, maxKeepAliveRequests);
    }

    public ServerOptions maxBodyBytes(long bytes) {
        return new ServerOptions(host, port, backlog, idleTimeoutMillis, maxRequestLineBytes,
                maxHeaderBytes, maxHeaderCount, bytes, readBufferBytes, maxKeepAliveRequests);
    }
}
