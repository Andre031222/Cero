/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro de datasources adicionales para soporte multi-datasource.
 * Usar junto con {@link JxMapping.JxDS} y {@link JxDB#JxDB(String)}.
 *
 * <pre>
 *   // En AppConfig o ServletContextListener:
 *   JxDataSourceRegistry.register("reporting",
 *       "jdbc:postgresql://reports-db:5432/reports", "user", "pass");
 *
 *   // Uso directo:
 *   try (JxDB db = new JxDB("reporting")) {
 *       DBRowSet rows = db.query("SELECT * FROM sales_summary");
 *   }
 *
 *   // Vía DI con @JxDS:
 *   &#64;JxService
 *   &#64;JxDS("reporting")
 *   public class ReportService {
 *       &#64;JxInject JxDB db;   // JxDB apuntará al datasource "reporting"
 *   }
 * </pre>
 */
public final class JxDataSourceRegistry {

    private static final ConcurrentHashMap<String, DSConfig> REGISTRY = new ConcurrentHashMap<>();

    private JxDataSourceRegistry() {}

    /**
     * Registra un datasource con nombre.
     *
     * @param name nombre lógico (ej. {@code "reporting"})
     * @param url  JDBC URL
     * @param user usuario de BD
     * @param pass contraseña de BD
     */
    public static void register(String name, String url, String user, String pass) {
        REGISTRY.put(name, new DSConfig(url, user, pass));
    }

    /**
     * Registra desde propiedades con prefijo.
     * Lee {@code <prefix>.url}, {@code <prefix>.user}, {@code <prefix>.pass}.
     *
     * <pre>
     *   # application.properties
     *   reporting.url=jdbc:postgresql://...
     *   reporting.user=reporter
     *   reporting.pass=secret
     * </pre>
     */
    public static void registerFromProperties(String name, String prefix) {
        String url  = BaseDbResolver.property(prefix + ".url",  "");
        String user = BaseDbResolver.property(prefix + ".user", "");
        String pass = BaseDbResolver.property(prefix + ".pass", "");
        if (!url.isBlank()) register(name, url, user, pass);
    }

    /** Retorna la configuración del datasource, o {@code null} si no existe. */
    public static DSConfig get(String name) {
        return REGISTRY.get(name);
    }

    /** {@code true} si el datasource está registrado. */
    public static boolean has(String name) {
        return REGISTRY.containsKey(name);
    }

    /** Elimina un datasource del registro. */
    public static void unregister(String name) {
        REGISTRY.remove(name);
    }

    // ── Configuración ─────────────────────────────────────────────────────

    public record DSConfig(String url, String user, String pass) {}
}
