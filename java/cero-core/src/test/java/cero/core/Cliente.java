package cero.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class Cliente {

    private Cliente() {
    }

    static HttpResponse<String> get(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    static HttpResponse<String> get(String url, String header, String value) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).header(header, value).GET());
    }

    static HttpResponse<String> post(String url, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    static HttpResponse<String> method(String url, String verb) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url))
                .method(verb, HttpRequest.BodyPublishers.noBody()));
    }

    /** Para respuestas que no son texto: un jar, un zip, una imagen. */
    static HttpResponse<byte[]> bytes(String url) throws Exception {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(HttpRequest.newBuilder(URI.create(url))
                                .version(HttpClient.Version.HTTP_1_1).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request.version(HttpClient.Version.HTTP_1_1).build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
