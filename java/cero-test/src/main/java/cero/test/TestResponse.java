package cero.test;

import cero.core.Json;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TestResponse {

    private static final int EXTRACTO = 500;

    private final String peticion;
    private final HttpResponse<String> response;

    TestResponse(String peticion, HttpResponse<String> response) {
        this.peticion = peticion;
        this.response = response;
    }

    public int status() {
        return response.statusCode();
    }

    public String body() {
        return response.body();
    }

    public String header(String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    public HttpClient.Version version() {
        return response.version();
    }

    public HttpResponse<String> raw() {
        return response;
    }

    public Object json() {
        return Json.read(response.body());
    }

    public Object json(String path) {
        Object node = json();
        for (String step : path.split("\\.")) {
            node = descend(node, step, path);
        }
        return node;
    }

    public TestResponse ok() {
        return status(200);
    }

    public TestResponse status(int expected) {
        if (status() != expected) {
            throw fail("estado " + expected, String.valueOf(status()));
        }
        return this;
    }

    public TestResponse header(String name, String expected) {
        String actual = header(name);
        if (!expected.equals(actual)) {
            throw fail("cabecera " + name + " = <" + expected + ">", "<" + actual + ">");
        }
        return this;
    }

    public TestResponse contentType(String expected) {
        String actual = header("Content-Type");
        String limpio = actual == null ? null : actual.split(";")[0].trim();
        if (!expected.equals(limpio)) {
            throw fail("Content-Type <" + expected + ">", "<" + actual + ">");
        }
        return this;
    }

    public TestResponse contains(String fragment) {
        if (response.body() == null || !response.body().contains(fragment)) {
            throw fail("un cuerpo que contenga <" + fragment + ">", "uno que no lo contiene");
        }
        return this;
    }

    public TestResponse json(String path, Object expected) {
        Object actual = json(path);
        if (!igual(actual, expected)) {
            throw fail("json " + path + " = <" + expected + ">", "<" + actual + ">");
        }
        return this;
    }

    public TestResponse cookie(String name) {
        for (String enviada : response.headers().allValues("Set-Cookie")) {
            if (enviada.startsWith(name + "=")) {
                return this;
            }
        }
        throw fail("la cookie " + name, "Set-Cookie: " + response.headers().allValues("Set-Cookie"));
    }

    private static Object descend(Object node, String step, String path) {
        return switch (node) {
            case Map<?, ?> objeto -> objeto.get(step);
            case List<?> lista -> {
                int indice = Integer.parseInt(step);
                yield indice < lista.size() ? lista.get(indice) : null;
            }
            case null -> null;
            default -> throw new AssertionError("la ruta json <" + path + "> se sale en <" + step
                    + ">: ahí no hay objeto ni lista sino " + node.getClass().getSimpleName());
        };
    }

    private static boolean igual(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        return Objects.equals(actual, expected);
    }

    private AssertionError fail(String esperado, String obtenido) {
        String cuerpo = response.body() == null ? "" : response.body();
        return new AssertionError(peticion + ": se esperaba " + esperado + " y llegó " + obtenido
                + "\n  estado: " + status()
                + "\n  cuerpo: " + (cuerpo.length() > EXTRACTO
                        ? cuerpo.substring(0, EXTRACTO) + "… (" + cuerpo.length() + " bytes)"
                        : cuerpo));
    }
}
