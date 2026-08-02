/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  v3.1.1   : R. Andre Vilca Solorzano

package jxmvc.core;

import java.io.InputStream;
import java.util.Properties;

/**
 * Lee la configuración desde {@code application.properties}.
 *
 * <p><b>Prioridad (mayor a menor):</b>
 * <ol>
 *   <li>Override en runtime vía {@link #setOverride} (multi-tenant, tests)
 *   <li>Variables de entorno del sistema operativo ({@code DB_URL}, {@code DB_USER}, etc.)
 *   <li>Propiedades Java {@code -Djxmvc.db.url=...}
 *   <li>{@code application.properties} en el classpath
 *   <li>Valor por defecto del framework
 * </ol>
 *
 * <pre>
 * # application.properties
 * jxmvc.db.url=jdbc:postgresql://localhost:5432/miapp
 * jxmvc.db.user=admin
 * jxmvc.db.pass=secreto
 * jxmvc.pool.enabled=true
 * jxmvc.pool.size=10
 * jxmvc.profile=dev
 *
 * # También se puede configurar con variables de entorno:
 * # DB_URL, DB_USER, DB_PASS, DB_DRIVER
 * </pre>
 */
public final class BaseDbResolver {

    private static final java.util.logging.Logger cfgLog =
            java.util.logging.Logger.getLogger(BaseDbResolver.class.getName());
    private static final Properties props = loadProps();

    private static volatile String overrideDriver;
    private static volatile String overrideUrl;
    private static volatile String overrideUser;
    private static volatile String overridePass;

    private BaseDbResolver() {}

    // ── Conexión BD ───────────────────────────────────────────────────────

    public static String driver() {
        return resolve(overrideDriver, "DB_DRIVER", "jxmvc.db.driver", "org.postgresql.Driver");
    }
    public static String url() {
        return resolve(overrideUrl,    "DB_URL",    "jxmvc.db.url",    "jdbc:postgresql://localhost:5432/appdb");
    }
    public static String user() {
        return resolve(overrideUser,   "DB_USER",   "jxmvc.db.user",   "app");
    }
    public static String pass() {
        return resolve(overridePass,   "DB_PASS",   "jxmvc.db.pass",   "app");
    }

    // ── Paquetes ──────────────────────────────────────────────────────────

    public static String controllerPackage() { return prop("jxmvc.controllers.package", "jxmvc.controllers"); }
    public static String servicesPackage()   { return prop("jxmvc.services.package",    "jxmvc.services"); }

    // ── Pool ──────────────────────────────────────────────────────────────

    public static boolean poolEnabled()  { return "true".equalsIgnoreCase(prop("jxmvc.pool.enabled",  "false")); }
    public static int     poolSize()     { return parseInt(prop("jxmvc.pool.size",    "10"), 10); }
    public static int     poolTimeout()  { return parseInt(prop("jxmvc.pool.timeout", "30"), 30); }

    // ── API general ───────────────────────────────────────────────────────

    /** Lee cualquier propiedad del {@code application.properties} con valor por defecto. */
    public static String property(String key, String defaultValue) {
        return prop(key, defaultValue);
    }

    /** Lee una propiedad como entero. */
    public static int propertyInt(String key, int defaultValue) {
        return parseInt(prop(key, String.valueOf(defaultValue)), defaultValue);
    }

    /** Lee una propiedad como boolean. */
    public static boolean propertyBool(String key, boolean defaultValue) {
        String v = prop(key, null);
        return v != null ? "true".equalsIgnoreCase(v.trim()) : defaultValue;
    }

    // ── Override en runtime ───────────────────────────────────────────────

    /** Sobreescribe la conexión en runtime — útil para multi-tenant o tests. */
    public static void setOverride(String url, String user, String pass, String driver) {
        overrideUrl    = url;
        overrideUser   = user;
        overridePass   = pass;
        overrideDriver = driver;
    }

    /** Elimina el override y vuelve a usar application.properties. */
    public static void clearOverride() {
        overrideUrl = overrideUser = overridePass = overrideDriver = null;
    }

    // ── Internos ─────────────────────────────────────────────────────────

    /**
     * Resolución con prioridad:
     * 1. Override en memoria  2. System property (-D)  3. Env var  4. application.properties  5. fallback
     */
    private static String resolve(String override, String envVar, String propKey, String fallback) {
        if (override != null && !override.isBlank()) return override.trim();

        String sys = System.getProperty(propKey);
        if (sys != null && !sys.isBlank()) return sys.trim();

        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) return env.trim();

        return prop(propKey, fallback);
    }

    private static String prop(String key, String fallback) {
        String v = props.getProperty(key);
        if (v != null && !v.isBlank()) return v.trim();
        // Intentar también con variable de entorno derivada del key
        String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String env = System.getenv(envKey);
        return (env != null && !env.isBlank()) ? env.trim() : fallback;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static Properties loadProps() {
        Properties p = new Properties();
        String profile = System.getProperty("jxmvc.profile",
                         System.getenv("JXMVC_PROFILE") != null
                             ? System.getenv("JXMVC_PROFILE") : "");
        if (!profile.isBlank()) {
            try (InputStream in = BaseDbResolver.class.getClassLoader()
                    .getResourceAsStream("application-" + profile + ".properties")) {
                if (in != null) p.load(in);
            } catch (Exception ignored) {}
        }
        try (InputStream in = BaseDbResolver.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                Properties base = new Properties();
                base.load(in);
                base.forEach(p::putIfAbsent);
            } else if (p.isEmpty()) {
                cfgLog.warning("application.properties no encontrado en classpath — usando valores por defecto. " +
                               "Configure DB_URL, DB_USER, DB_PASS como variables de entorno o " +
                               "añada application.properties a src/main/resources/");
            }
        } catch (Exception ignored) {}
        return p;
    }
}
