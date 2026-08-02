package lux.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class Http {

    private static final HttpClient CLIENTE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String url;
    private final Map<String, String> cabeceras = new LinkedHashMap<>();
    private final Map<String, String> consulta = new LinkedHashMap<>();

    private Duration espera = Duration.ofSeconds(30);
    private int reintentos;
    private Duration pausa = Duration.ofMillis(200);

    private Http(String url) {
        this.url = url;
    }

    public static Http to(String url) {
        return new Http(url);
    }

    public Http header(String nombre, String valor) {
        cabeceras.put(nombre, valor);
        return this;
    }

    public Http bearer(String token) {
        return header("Authorization", "Bearer " + token);
    }

    public Http query(String nombre, Object valor) {
        consulta.put(nombre, String.valueOf(valor));
        return this;
    }

    public Http timeout(Duration valor) {
        espera = valor;
        return this;
    }

    /** Reintenta ante fallo de red o respuesta 5xx, con espera creciente. */
    public Http retry(int veces) {
        reintentos = Math.max(0, veces);
        return this;
    }

    public Http retryDelay(Duration valor) {
        pausa = valor;
        return this;
    }

    public Respuesta get() {
        return enviar("GET", HttpRequest.BodyPublishers.noBody(), null);
    }

    public Respuesta delete() {
        return enviar("DELETE", HttpRequest.BodyPublishers.noBody(), null);
    }

    public Respuesta post(Object cuerpo) {
        return conCuerpo("POST", cuerpo);
    }

    public Respuesta put(Object cuerpo) {
        return conCuerpo("PUT", cuerpo);
    }

    public Respuesta patch(Object cuerpo) {
        return conCuerpo("PATCH", cuerpo);
    }

    public Respuesta postText(String cuerpo) {
        return enviar("POST", HttpRequest.BodyPublishers.ofString(cuerpo), "text/plain; charset=utf-8");
    }

    public Respuesta postForm(Map<String, ?> campos) {
        StringBuilder codificado = new StringBuilder();
        campos.forEach((clave, valor) -> {
            if (!codificado.isEmpty()) {
                codificado.append('&');
            }
            codificado.append(escapar(clave)).append('=').append(escapar(String.valueOf(valor)));
        });
        return enviar("POST", HttpRequest.BodyPublishers.ofString(codificado.toString()),
                "application/x-www-form-urlencoded");
    }

    /**
     * Consume una respuesta de eventos servidos (SSE), que es como transmiten las APIs de IA.
     * Entrega cada bloque {@code data:} tal cual; el centinela [DONE] cierra el flujo.
     */
    public void sse(Object cuerpo, Consumer<String> porEvento) {
        HttpRequest peticion = construir(cuerpo == null ? "GET" : "POST",
                cuerpo == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Json.write(cuerpo)),
                cuerpo == null ? null : "application/json",
                "text/event-stream");
        try {
            HttpResponse<java.io.InputStream> respuesta =
                    CLIENTE.send(peticion, HttpResponse.BodyHandlers.ofInputStream());

            if (respuesta.statusCode() >= 400) {
                throw new HttpClientException(respuesta.statusCode(),
                        new String(respuesta.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var lector = new java.io.BufferedReader(
                    new java.io.InputStreamReader(respuesta.body(), StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = lector.readLine()) != null) {
                    if (!linea.startsWith("data:")) {
                        continue;
                    }
                    String dato = linea.substring(5).trim();
                    if (dato.equals("[DONE]")) {
                        return;
                    }
                    if (!dato.isEmpty()) {
                        porEvento.accept(dato);
                    }
                }
            }
        } catch (IOException fallo) {
            throw new UncheckedIOException("falló el flujo de eventos a " + url, fallo);
        } catch (InterruptedException cortado) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrumpido durante el flujo a " + url, cortado);
        }
    }

    private Respuesta conCuerpo(String verbo, Object cuerpo) {
        String json = cuerpo instanceof String texto ? texto : Json.write(cuerpo);
        return enviar(verbo, HttpRequest.BodyPublishers.ofString(json), "application/json");
    }

    private Respuesta enviar(String verbo, HttpRequest.BodyPublisher cuerpo, String tipo) {
        HttpRequest peticion = construir(verbo, cuerpo, tipo, null);
        RuntimeException ultimo = null;

        for (int intento = 0; intento <= reintentos; intento++) {
            if (intento > 0) {
                dormir(pausa.toMillis() * intento);
            }
            try {
                HttpResponse<String> respuesta =
                        CLIENTE.send(peticion, HttpResponse.BodyHandlers.ofString());
                if (respuesta.statusCode() >= 500 && intento < reintentos) {
                    ultimo = new HttpClientException(respuesta.statusCode(), respuesta.body());
                    continue;
                }
                return new Respuesta(respuesta.statusCode(), respuesta.body(),
                        respuesta.headers().map());
            } catch (IOException fallo) {
                ultimo = new UncheckedIOException("falló la petición a " + url, fallo);
            } catch (InterruptedException cortado) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrumpido llamando a " + url, cortado);
            }
        }
        throw ultimo;
    }

    private HttpRequest construir(String verbo, HttpRequest.BodyPublisher cuerpo,
                                  String tipo, String acepta) {
        HttpRequest.Builder constructor = HttpRequest.newBuilder(URI.create(direccionCompleta()))
                .timeout(espera)
                .method(verbo, cuerpo);

        if (tipo != null) {
            constructor.header("Content-Type", tipo);
        }
        if (acepta != null) {
            constructor.header("Accept", acepta);
        }
        cabeceras.forEach(constructor::header);
        return constructor.build();
    }

    String direccionCompleta() {
        if (consulta.isEmpty()) {
            return url;
        }
        StringBuilder completa = new StringBuilder(url);
        completa.append(url.indexOf('?') >= 0 ? '&' : '?');
        boolean primero = true;
        for (Map.Entry<String, String> par : consulta.entrySet()) {
            if (!primero) {
                completa.append('&');
            }
            primero = false;
            completa.append(escapar(par.getKey())).append('=').append(escapar(par.getValue()));
        }
        return completa.toString();
    }

    private static String escapar(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8);
    }

    private static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException cortado) {
            Thread.currentThread().interrupt();
        }
    }

    public record Respuesta(int estado, String cuerpo, Map<String, List<String>> cabeceras) {

        public boolean ok() {
            return estado >= 200 && estado < 300;
        }

        public String cabecera(String nombre) {
            for (Map.Entry<String, List<String>> par : cabeceras.entrySet()) {
                if (par.getKey().equalsIgnoreCase(nombre) && !par.getValue().isEmpty()) {
                    return par.getValue().get(0);
                }
            }
            return null;
        }

        public Object json() {
            return Json.read(cuerpo);
        }

        public <T> T as(Class<T> tipo) {
            return Json.read(cuerpo, tipo);
        }

        /** Devuelve el cuerpo si la respuesta fue correcta; si no, lanza con el detalle. */
        public String requerido() {
            if (!ok()) {
                throw new HttpClientException(estado, cuerpo);
            }
            return cuerpo;
        }

        @SuppressWarnings("unchecked")
        public List<Object> lista() {
            Object arbol = json();
            return arbol instanceof List<?> items ? new ArrayList<>((List<Object>) items) : List.of();
        }
    }

    public static final class HttpClientException extends RuntimeException {

        private final int estado;

        public HttpClientException(int estado, String cuerpo) {
            super("respuesta " + estado + ": " + recortar(cuerpo));
            this.estado = estado;
        }

        public int estado() {
            return estado;
        }

        private static String recortar(String cuerpo) {
            if (cuerpo == null) {
                return "";
            }
            return cuerpo.length() <= 300 ? cuerpo : cuerpo.substring(0, 300) + "…";
        }
    }
}
