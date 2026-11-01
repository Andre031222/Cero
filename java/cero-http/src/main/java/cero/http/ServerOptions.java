package cero.http;

import javax.net.ssl.SSLContext;

public record ServerOptions(
        String host,
        int port,
        int backlog,
        int maxConnections,
        int idleTimeoutMillis,
        int handlerTimeoutMillis,
        int writeTimeoutMillis,
        int shutdownGraceMillis,
        int maxRequestLineBytes,
        int maxHeaderBytes,
        int maxHeaderCount,
        long maxBodyBytes,
        int readBufferBytes,
        int maxKeepAliveRequests,
        boolean http2,
        int http2MaxFlujos,
        int http2MaxBloqueCabeceras,
        int http2MaxListaCabeceras,
        int http2MaxAnulados,
        int http2MaxControlSeguidas,
        int http2MaxEnVuelo,
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
        if (idleTimeoutMillis <= 0 || handlerTimeoutMillis <= 0 || writeTimeoutMillis <= 0
                || maxBodyBytes <= 0 || http2MaxEnVuelo <= 0) {
            throw new IllegalArgumentException("los plazos y los topes tienen que ser mayores que cero");
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
                .writeTimeoutMillis(writeTimeoutMillis)
                .shutdownGraceMillis(shutdownGraceMillis).maxRequestLineBytes(maxRequestLineBytes)
                .maxHeaderBytes(maxHeaderBytes).maxHeaderCount(maxHeaderCount)
                .maxBodyBytes(maxBodyBytes).readBufferBytes(readBufferBytes)
                .maxKeepAliveRequests(maxKeepAliveRequests)
                .http2(http2).http2MaxFlujos(http2MaxFlujos)
                .http2MaxBloqueCabeceras(http2MaxBloqueCabeceras)
                .http2MaxListaCabeceras(http2MaxListaCabeceras)
                .http2MaxAnulados(http2MaxAnulados)
                .http2MaxControlSeguidas(http2MaxControlSeguidas)
                .http2MaxEnVuelo(http2MaxEnVuelo)
                .sessionTimeoutMillis(sessionTimeoutMillis)
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
        private int writeTimeoutMillis = 60_000;
        private int shutdownGraceMillis = 10_000;
        private int maxRequestLineBytes = 8_192;
        private int maxHeaderBytes = 32_768;
        private int maxHeaderCount = 100;
        private long maxBodyBytes = 10L << 20;
        private int readBufferBytes = 16_384;
        private int maxKeepAliveRequests = 1_000;

        private boolean http2 = true;
        private int http2MaxFlujos = 128;
        private int http2MaxBloqueCabeceras = 64 * 1024;
        private int http2MaxListaCabeceras = 32 * 1024;
        private int http2MaxAnulados = 100;
        private int http2MaxControlSeguidas = 1_000;
        private int http2MaxEnVuelo = 32 << 20;
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

        public Builder writeTimeoutMillis(int value) {
            writeTimeoutMillis = value;
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

        public Builder http2(boolean value) {
            http2 = value;
            return this;
        }

        public Builder http2MaxFlujos(int value) {
            http2MaxFlujos = value;
            return this;
        }

        public Builder http2MaxBloqueCabeceras(int value) {
            http2MaxBloqueCabeceras = value;
            return this;
        }

        public Builder http2MaxListaCabeceras(int value) {
            http2MaxListaCabeceras = value;
            return this;
        }

        public Builder http2MaxAnulados(int value) {
            http2MaxAnulados = value;
            return this;
        }

        public Builder http2MaxControlSeguidas(int value) {
            http2MaxControlSeguidas = value;
            return this;
        }

        public Builder http2MaxEnVuelo(int value) {
            http2MaxEnVuelo = value;
            return this;
        }

        public Builder maxKeepAliveRequests(int value) {
            maxKeepAliveRequests = value;
            return this;
        }

        public Builder sessionStore(SessionStore value) {
            sessionStore = value;
            return this;
        }

        public Builder sessionTimeoutMillis(int value) {
            sessionTimeoutMillis = value;
            return this;
        }

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

        public Builder behindProxy(boolean value) {
            behindProxy = value;
            return this;
        }

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
                    handlerTimeoutMillis, writeTimeoutMillis, shutdownGraceMillis, maxRequestLineBytes, maxHeaderBytes,
                    maxHeaderCount, maxBodyBytes, readBufferBytes, maxKeepAliveRequests,
                    http2, http2MaxFlujos, http2MaxBloqueCabeceras, http2MaxListaCabeceras,
                    http2MaxAnulados, http2MaxControlSeguidas, http2MaxEnVuelo,
                    sessionTimeoutMillis, sessionMaxLifetimeMillis, sessionStore,
                    gzipMinBytes, requireHost, behindProxy, trustedProxies, tls);
        }
    }
}
