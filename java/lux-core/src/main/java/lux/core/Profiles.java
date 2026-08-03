package lux.core;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Perfiles de ejecución: qué versión de la aplicación se está levantando.
 *
 * <pre>
 *   # application.properties
 *   lux.profiles = prod,metricas
 * </pre>
 *
 * <p>Se puede pisar por propiedad del sistema (<code>-Dlux.profiles=dev</code>) o por entorno
 * (<code>LUX_PROFILES=dev</code>), en ese orden de prioridad. Sin nada configurado el perfil es
 * <code>default</code>.
 *
 * <pre>
 *   Profiles perfiles = Profiles.from(config);
 *
 *   if (perfiles.dev()) {
 *       plantillas.reload(true);      // recompilar en cada petición
 *   }
 *   perfiles.onlyIn("prod", () -&gt; app.use(SecurityHeaders.standard()));
 * </pre>
 *
 * <p>Es un objeto, no un bloque estático: una prueba puede montar el suyo sin reiniciar la JVM.
 * Del «modo desarrollo» heredado queda {@link #dev()}; qué hacer con él —recompilar plantillas,
 * enseñar el error completo— lo decide la aplicación.
 */
public final class Profiles {

    public static final String DEFECTO = "default";

    private final Set<String> activos;

    private Profiles(Set<String> activos) {
        this.activos = activos;
    }

    /** Perfiles declarados a mano. Útil en pruebas. */
    public static Profiles of(String... nombres) {
        return new Profiles(normalizar(String.join(",", nombres)));
    }

    /** Perfiles de la configuración, pisados por {@code -Dlux.profiles} y por {@code LUX_PROFILES}. */
    public static Profiles from(Config config) {
        String declarado = System.getProperty("lux.profiles");
        if (declarado == null) {
            declarado = System.getenv("LUX_PROFILES");
        }
        if (declarado == null && config != null) {
            declarado = config.get("lux.profiles", null);
        }
        return new Profiles(normalizar(declarado));
    }

    /** El primero de los activos, o {@code default} si no hay ninguno. */
    public String active() {
        return activos.isEmpty() ? DEFECTO : activos.iterator().next();
    }

    /** Todos los activos. Nunca vacío: sin nada configurado contiene {@code default}. */
    public Set<String> all() {
        return activos.isEmpty() ? Set.of(DEFECTO) : Set.copyOf(activos);
    }

    /** ¿Está activo este perfil? Sin distinguir mayúsculas. */
    public boolean is(String nombre) {
        return nombre != null && all().contains(nombre.trim().toLowerCase());
    }

    /** ¿Alguno de estos? */
    public boolean any(String... nombres) {
        for (String nombre : nombres) {
            if (is(nombre)) {
                return true;
            }
        }
        return false;
    }

    /** Atajo para el perfil de desarrollo, que admite los dos nombres de siempre. */
    public boolean dev() {
        return any("dev", "development");
    }

    /** Atajo para producción. */
    public boolean prod() {
        return any("prod", "production");
    }

    /** Ejecuta la acción solo si el perfil está activo. Devuelve si llegó a ejecutarla. */
    public boolean onlyIn(String nombre, Runnable accion) {
        if (!is(nombre)) {
            return false;
        }
        accion.run();
        return true;
    }

    @Override
    public String toString() {
        return String.join(",", all());
    }

    private static Set<String> normalizar(String declarado) {
        Set<String> nombres = new LinkedHashSet<>();
        if (declarado == null || declarado.isBlank()) {
            return nombres;
        }
        for (String parte : declarado.split(",")) {
            String limpio = parte.trim().toLowerCase();
            if (!limpio.isEmpty()) {
                nombres.add(limpio);
            }
        }
        return nombres;
    }
}
