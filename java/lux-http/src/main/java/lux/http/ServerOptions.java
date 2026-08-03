package lux.http;

import javax.net.ssl.SSLContext;

public record ServerOptions(
        String host,
        int port,
        int backlog,
        int maxConnections,
        int idleTimeoutMillis,
        int handlerTimeoutMillis,
        int shutdownGraceMillis,
        int maxRequestLineBytes,
        int maxHeaderBytes,
        int maxHeaderCount,
        long maxBodyBytes,
        int readBufferBytes,
        int maxKeepAliveRequests,
        int sessionTimeoutMillis,
        long sessionMaxLifetimeMillis,
        SessionStore sessionStore,
        int gzipMinBytes,
        boolean requireHost,
        boolean behindProxy,
        java.util.Set<String> trustedProxies,
        SSLContext tls) {

    public ServerOptions {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("puerto fuera de rango: " + port);
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections debe ser al menos 1");
        }
        if (readBufferBytes < 512) {
            throw new IllegalArgumentException("buffer de lectura demasiado pequeño: " + readBufferBytes);
        }
        if (maxRequestLineBytes < 64 || maxHeaderBytes < 64) {
            throw new IllegalArgumentException("límites de cabecera demasiado pequeños");
        }
        if (maxBodyBytes < 0) {
            throw new IllegalArgumentException("maxBodyBytes negativo");
        }
    }

    public static ServerOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean secure() {
        return tls != null;
    }

    public Builder toBuilder() {
        return new Builder()
                .host(host).port(port).backlog(backlog).maxConnections(maxConnections)
                .idleTimeoutMillis(idleTimeoutMillis).handlerTimeoutMillis(handlerTimeoutMillis)
                .shutdownGraceMillis(shutdownGraceMillis).maxRequestLineBytes(maxRequestLineBytes)
                .maxHeaderBytes(maxHeaderBytes).maxHeaderCount(maxHeaderCount)
                .maxBodyBytes(maxBodyBytes).readBufferBytes(readBufferBytes)
                .maxKeepAliveRequests(maxKeepAliveRequests).sessionTimeoutMillis(sessionTimeoutMillis)
                .sessionMaxLifetime(sessionMaxLifetimeMillis)
                .sessionStore(sessionStore)
                .gzipMinBytes(gzipMinBytes).requireHost(requireHost).behindProxy(behindProxy).trustProxy(trustedProxies).tls(tls);
    }

    public static final class Builder {

        private String host = "0.0.0.0";
        private int port = 8080;
        private int backlog = 1_024;
        private int maxConnections = 10_000;
        private int idleTimeoutMillis = 30_000;
        private int handlerTimeoutMillis = 30_000;
        private int shutdownGraceMillis = 10_000;
        private int maxRequestLineBytes = 8_192;
        private int maxHeaderBytes = 32_768;
        private int maxHeaderCount = 100;
        private long maxBodyBytes = 10L << 20;
        private int readBufferBytes = 16_384;
        private int maxKeepAliveRequests = 1_000;
        private int sessionTimeoutMillis = 30 * 60_000;
        private long sessionMaxLifetimeMillis;
        private SessionStore sessionStore;
        private int gzipMinBytes = 1_024;
        private boolean requireHost = true;
        private boolean behindProxy;
        private java.util.Set<String> trustedProxies = java.util.Set.of();
        private SSLContext tls;

        public Builder host(String value) {
            host = value;
            return this;
        }

        public Builder port(int value) {
            port = value;
            return this;
        }

        public Builder backlog(int value) {
            backlog = value;
            return this;
        }

        public Builder maxConnections(int value) {
            maxConnections = value;
            return this;
        }

        public Builder idleTimeoutMillis(int value) {
            idleTimeoutMillis = value;
            return this;
        }

        public Builder handlerTimeoutMillis(int value) {
            handlerTimeoutMillis = value;
            return this;
        }

        public Builder shutdownGraceMillis(int value) {
            shutdownGraceMillis = value;
            return this;
        }

        public Builder maxRequestLineBytes(int value) {
            maxRequestLineBytes = value;
            return this;
        }

        public Builder maxHeaderBytes(int value) {
            maxHeaderBytes = value;
            return this;
        }

        public Builder maxHeaderCount(int value) {
            maxHeaderCount = value;
            return this;
        }

        public Builder maxBodyBytes(long value) {
            maxBodyBytes = value;
            return this;
        }

        public Builder readBufferBytes(int value) {
            readBufferBytes = value;
            return this;
        }

        public Builder maxKeepAliveRequests(int value) {
            maxKeepAliveRequests = value;
            return this;
        }

        /** Dónde viven las sesiones. Sin declararlo, en la memoria de este proceso. */
        public Builder sessionStore(SessionStore value) {
            sessionStore = value;
            return this;
        }

        public Builder sessionTimeoutMillis(int value) {
            sessionTimeoutMillis = value;
            return this;
        }

        /**
         * Tope de vida total de una sesión, se use o no. Apagado por defecto: solo cuenta la
         * inactividad. Para un panel de administración conviene ponerlo — sin él, una sesión que
         * se toque de vez en cuando no caduca nunca.
         */
        public Builder sessionMaxLifetime(java.time.Duration value) {
            sessionMaxLifetimeMillis = value == null ? 0 : value.toMillis();
            return this;
        }

        Builder sessionMaxLifetime(long millis) {
            sessionMaxLifetimeMillis = millis;
            return this;
        }

        public Builder gzipMinBytes(int value) {
            gzipMinBytes = value;
            return this;
        }

        /**
         * Declara que delante hay un proxy inverso de confianza. Con esto se hace caso a
         * {@code X-Forwarded-Proto} para saber si la petición original venía por HTTPS, y la
         * cookie de sesión se marca {@code Secure} como corresponde.
         */
        public Builder behindProxy(boolean value) {
            behindProxy = value;
            return this;
        }

        /**
         * Direcciones de los proxies en los que se confía. Solo si la petición viene de una de
         * ellas se hace caso a {@code X-Forwarded-For} para saber la IP real del visitante.
         *
         * <p>Nunca está activa por defecto, y no puede estarlo: creerse esa cabecera venga de
         * donde venga permite a cualquiera decir que es otra IP, y con eso se salta el limitador
         * de peticiones y se ensucian los registros.
         *
         * <pre>{@code ServerOptions.builder().behindProxy(true).trustProxy("127.0.0.1", "::1")}</pre>
         */
        public Builder trustProxy(String... direcciones) {
            trustedProxies = direcciones == null ? java.util.Set.of() : java.util.Set.of(direcciones);
            behindProxy = behindProxy || trustedProxies.size() > 0;
            return this;
        }

        Builder trustProxy(java.util.Set<String> direcciones) {
            trustedProxies = direcciones == null ? java.util.Set.of() : direcciones;
            return this;
        }

        public Builder requireHost(boolean value) {
            requireHost = value;
            return this;
        }

        public Builder tls(SSLContext value) {
            tls = value;
            return this;
        }

        public ServerOptions build() {
            return new ServerOptions(host, port, backlog, maxConnections, idleTimeoutMillis,
                    handlerTimeoutMillis, shutdownGraceMillis, maxRequestLineBytes, maxHeaderBytes,
                    maxHeaderCount, maxBodyBytes, readBufferBytes, maxKeepAliveRequests,
                    sessionTimeoutMillis, sessionMaxLifetimeMillis, sessionStore,
                    gzipMinBytes, requireHost, behindProxy, trustedProxies, tls);
        }
    }
}
