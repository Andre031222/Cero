package cero.test;

import cero.core.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TestClient implements AutoCloseable {

    private final String base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private HttpClient.Version version;

    private TestClient(String base) {
        this.base = base;
        this.client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static TestClient of(String baseUrl) {
        return new TestClient(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
    }

    public TestClient http1() {
        version = HttpClient.Version.HTTP_1_1;
        return this;
    }

    public TestClient http2() {
        version = HttpClient.Version.HTTP_2;
        return this;
    }

    public TestClient header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public TestResponse get(String path) {
        return send("GET", path, null, null);
    }

    public TestResponse delete(String path) {
        return send("DELETE", path, null, null);
    }

    public TestResponse post(String path) {
        return send("POST", path, null, null);
    }

    public TestResponse post(String path, Object json) {
        return send("POST", path, "application/json", json(json));
    }

    public TestResponse put(String path, Object json) {
        return send("PUT", path, "application/json", json(json));
    }

    public TestResponse patch(String path, Object json) {
        return send("PATCH", path, "application/json", json(json));
    }

    public TestResponse form(String path, Map<String, String> fields) {
        return send("POST", path, "application/x-www-form-urlencoded", urlencoded(fields));
    }

    public TestResponse send(String method, String path, String contentType, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            request.header("Content-Type", contentType + "; charset=utf-8");
        }
        headers.forEach(request::header);
        if (version != null) {
            request.version(version);
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new TestResponse(method + " " + path, response);
        } catch (IOException cause) {
            throw new UncheckedIOException(method + " " + path + " falló", cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " interrumpida", interrupted);
        }
    }

    public String cookie(String name) {
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }

    @Override
    public void close() {
        client.close();
    }

    private static String json(Object value) {
        return value instanceof String raw ? raw : Json.write(value);
    }

    private static String urlencoded(Map<String, String> fields) {
        var out = new StringBuilder();
        fields.forEach((name, value) -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return out.toString();
    }
}
