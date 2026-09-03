package cero.view;

import cero.core.ViewRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Templates implements ViewRenderer {

    private final Path directory;
    private final String classpathPrefix;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private String suffix = "";
    private boolean reload;

    private Templates(Path directory, String classpathPrefix) {
        this.directory = directory;
        this.classpathPrefix = classpathPrefix;
    }

    public static Templates from(Path directory) {
        return new Templates(directory.toAbsolutePath().normalize(), null);
    }

    public static Templates fromClasspath(String prefix) {
        String head = prefix.endsWith("/") || prefix.isEmpty() ? prefix : prefix + "/";
        return new Templates(null, head);
    }

    public Templates suffix(String value) {
        suffix = value;
        return this;
    }

    public Templates reload(boolean value) {
        reload = value;
        return this;
    }

    public void clearCache() {
        cache.clear();
    }

    public int cached() {
        return cache.size();
    }

    @Override
    public String render(String template, Object model) {
        return load(template).render(model, this);
    }

    /**
     * Rinde con valores disponibles en toda la plantilla sin pasar por el modelo.
     *
     * <p>Lo usa el despachador para poner {@code t}, el mapa de textos de la petición: una
     * plantilla escribe {@code &#123;&#123; t.guardar &#125;&#125;} sin que cada controlador
     * tenga que acordarse de meterlo en su modelo — y olvidarlo en uno solo deja esa página sin
     * traducir.
     */
    @Override
    public String render(String template, Object model, java.util.Map<String, Object> globals) {
        return load(template).render(model, this, globals);
    }

    Template load(String template) {
        String key = template.endsWith(suffix) ? template : template + suffix;
        Cached found = cache.get(key);
        if (found != null && !stale(found)) {
            return found.template();
        }
        Path file = directory == null ? null : resolve(key);
        Template compiled = Template.compile(key, read(key, file));
        cache.put(key, new Cached(compiled, file == null ? 0 : modified(file)));
        return compiled;
    }

    private boolean stale(Cached entry) {
        if (!reload || directory == null) {
            return false;
        }
        Path file = resolve(entry.template().name());
        return modified(file) != entry.modified();
    }

    private Path resolve(String key) {
        Path candidate = directory.resolve(key).normalize();
        if (!candidate.startsWith(directory)) {
            throw new TemplateException("la plantilla sale del directorio raíz: " + key);
        }
        return candidate;
    }

    private String read(String key, Path file) {
        if (file != null) {
            if (!Files.isRegularFile(file)) {
                throw new TemplateException("no existe la plantilla " + key + " en " + directory);
            }
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException cause) {
                throw new TemplateException("no se pudo leer " + key, cause);
            }
        }
        String resource = classpathPrefix + key;
        try (InputStream source = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (source == null) {
                throw new TemplateException("no existe la plantilla " + resource + " en el classpath");
            }
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new TemplateException("no se pudo leer " + resource, cause);
        }
    }

    private static long modified(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (IOException cause) {
            return -1;
        }
    }

    private record Cached(Template template, long modified) {
    }
}
