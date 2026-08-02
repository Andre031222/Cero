/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cliente HTTP ligero — cero dependencias externas (usa {@link HttpClient} de Java 11+).
 * Soporta GET, POST, PUT, DELETE, PATCH con cabeceras y cuerpo personalizados.
 *
 * <pre>
 *   // GET simple
 *   JxHttp.Response r = JxHttp.get("https://api.example.com/users");
 *   if (r.isOk()) { UserDto[] users = r.as(UserDto[].class); }
 *
 *   // POST JSON
 *   JxHttp.Response r = JxHttp.postJson("https://api.example.com/users",
 *                                        Map.of("name", "Ana", "email", "ana@mail.com"));
 *
 *   // Petición completa
 *   JxHttp.Response r = JxHttp.builder("PUT", "https://api.example.com/users/1")
 *                              .header("Authorization", "Bearer " + token)
 *                              .body(JxJson.toJson(dto))
 *                              .send();
 * </pre>
 */
public final class JxHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private JxHttp() {}

    // ── Métodos de conveniencia ───────────────────────────────────────────

    public static Response get(String url) {
        return builder("GET", url).send();
    }

    public static Response get(String url, Map<String, String> headers) {
        return builder("GET", url).headers(headers).send();
    }

    public static Response post(String url, String body) {
        return builder("POST", url).body(body).send();
    }

    public static Response postJson(String url, Object data) {
        return builder("POST", url)
                .header("Content-Type", "application/json")
                .body(JxJson.toJson(data))
                .send();
    }

    public static Response put(String url, String body) {
        return builder("PUT", url).body(body).send();
    }

    public static Response delete(String url) {
        return builder("DELETE", url).send();
    }

    /** Inicia una petición fluida. */
    public static Builder builder(String method, String url) {
        return new Builder(method, url);
    }

    // ── Builder ───────────────────────────────────────────────────────────

    public static final class Builder {
        private final String              method;
        private final String              url;
        private final Map<String, String> headers = new java.util.LinkedHashMap<>();
        private String                    body    = "";
        private int                       timeoutSecs = 30;

        private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB

        private Builder(String method, String url) {
            String scheme = URI.create(url).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                throw new IllegalArgumentException("JxHttp solo permite http/https: " + url);
            this.method = method.toUpperCase();
            this.url    = url;
        }

        public Builder header(String name, String value) { headers.put(name, value); return this; }

        public Builder headers(Map<String, String> h) { if (h != null) headers.putAll(h); return this; }

        public Builder body(String body) { this.body = body != null ? body : ""; return this; }

        public Builder timeout(int seconds) { this.timeoutSecs = seconds; return this; }

        public Response send() {
            try {
                HttpRequest.BodyPublisher publisher = body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8);

                HttpRequest.Builder req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSecs))
                        .method(method, publisher);

                if (!body.isEmpty() && !headers.containsKey("Content-Type")) {
                    req.header("Content-Type", "application/json; charset=UTF-8");
                }
                headers.forEach(req::header);

                HttpResponse<java.io.InputStream> resp = CLIENT.send(req.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                String bodyStr;
                try (java.io.InputStream is = resp.body()) {
                    long contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1);
                    if (contentLength > MAX_RESPONSE_BYTES)
                        return new Response(413, "Response demasiado grande", Map.of());
                    byte[] bytes = is.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES)
                        return new Response(413, "Response demasiado grande", Map.of());
                    bodyStr = new String(bytes, StandardCharsets.UTF_8);
                }
                return new Response(resp.statusCode(), bodyStr, resp.headers().map());

            } catch (Exception e) {
                return new Response(-1, e.getMessage(), Map.of());
            }
        }
    }

    // ── Response ──────────────────────────────────────────────────────────

    /**
     * Respuesta HTTP con código, cuerpo y cabeceras.
     */
    public static final class Response {
        private final int                          status;
        private final String                       body;
        private final Map<String, List<String>>    headers;

        Response(int status, String body, Map<String, List<String>> headers) {
            this.status  = status;
            this.body    = body != null ? body : "";
            this.headers = headers != null ? headers : Map.of();
        }

        public int    status()  { return status; }
        public String body()    { return body; }
        public Map<String, List<String>> headers() { return headers; }

        public boolean isOk()       { return status >= 200 && status < 300; }
        public boolean isClientError() { return status >= 400 && status < 500; }
        public boolean isServerError() { return status >= 500; }
        public boolean isError()    { return status < 0 || status >= 400; }

        /** Deserializa el cuerpo JSON al tipo indicado. */
        public <T> T as(Class<T> type) { return JxJson.fromJson(body, type); }

        /** Primer valor de la cabecera dada, o {@code null}. */
        public String header(String name) {
            List<String> vals = headers.get(name.toLowerCase());
            return (vals != null && !vals.isEmpty()) ? vals.get(0) : null;
        }

        @Override
        public String toString() {
            return "JxHttp.Response[status=" + status + ", body.length=" + body.length() + "]";
        }
    }
}
