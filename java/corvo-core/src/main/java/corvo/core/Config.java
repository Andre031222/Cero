package corvo.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class Config {

    /** El prefijo y su longitud van juntos a propósito: al renombrar el framework, un
     *  substring con el número escrito a mano se desincroniza en silencio. */
    private static final String PREFIJO_ENTORNO = "CORVO_";
    private static final String PREFIJO_PROPIEDAD = "corvo.";

    private final Map<String, String> values = new LinkedHashMap<>();

    private Config() {
    }

    public static Config empty() {
        return new Config();
    }

    public static Config load() {
        return load("application.properties");
    }

    public static Config load(String resource) {
        Config config = new Config();
        config.readClasspath(resource);
        config.readFile(Path.of(resource));
        config.readEnvironment();
        config.readSystemProperties();
        return config;
    }

    public Config set(String key, String value) {
        values.put(key, value);
        return this;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public String get(String key) {
        return values.get(key);
    }

    public String get(String key, String fallback) {
        String found = values.get(key);
        return found == null || found.isBlank() ? fallback : found;
    }

    public int getInt(String key, int fallback) {
        String found = get(key, null);
        return found == null ? fallback : Integer.parseInt(found.trim());
    }

    public long getLong(String key, long fallback) {
        String found = get(key, null);
        return found == null ? fallback : Long.parseLong(found.trim());
    }

    public boolean getBoolean(String key, boolean fallback) {
        String found = get(key, null);
        return found == null ? fallback : Boolean.parseBoolean(found.trim());
    }

    public Map<String, String> under(String prefix) {
        String head = prefix.endsWith(".") ? prefix : prefix + ".";
        Map<String, String> selected = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(head)) {
                selected.put(key.substring(head.length()), value);
            }
        });
        return selected;
    }

    public <T> T bind(Class<T> type, String prefix) {
        return Json.bind(new LinkedHashMap<String, Object>(under(prefix)), type);
    }

    public Map<String, String> asMap() {
        return Map.copyOf(values);
    }

    private void readClasspath(String resource) {
        try (InputStream source = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (source != null) {
                absorb(source);
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("no se pudo leer " + resource + " del classpath", cause);
        }
    }

    private void readFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream source = Files.newInputStream(path)) {
            absorb(source);
        } catch (IOException cause) {
            throw new UncheckedIOException("no se pudo leer " + path, cause);
        }
    }

    private void readEnvironment() {
        System.getenv().forEach((name, value) -> {
            String clave = claveDeEntorno(name);
            if (clave != null) {
                values.put(clave, value);
            }
        });
    }

    /**
     * Traduce una variable de entorno a clave de configuración, o devuelve {@code null} si no
     * lleva el prefijo. {@code CORVO_SERVER_PORT} pasa a ser {@code server.port}.
     *
     * <p>Está aparte para poder probarlo: {@code System.getenv()} no se puede tocar dentro del
     * proceso, así que si la traducción vive dentro del bucle no la cubre ninguna prueba. Ahí se
     * escondió un fallo al renombrar el framework — el prefijo pasó de cuatro letras a seis y el
     * recorte seguía escrito a mano.
     */
    static String claveDeEntorno(String nombre) {
        if (nombre == null || !nombre.startsWith(PREFIJO_ENTORNO)) {
            return null;
        }
        return nombre.substring(PREFIJO_ENTORNO.length()).toLowerCase().replace('_', '.');
    }

    private void readSystemProperties() {
        System.getProperties().forEach((key, value) -> {
            String name = String.valueOf(key);
            if (name.startsWith(PREFIJO_PROPIEDAD)) {
                values.put(name.substring(PREFIJO_PROPIEDAD.length()), String.valueOf(value));
            }
        });
    }

    private void absorb(InputStream source) throws IOException {
        Properties properties = new Properties();
        properties.load(source);
        properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
    }
}
