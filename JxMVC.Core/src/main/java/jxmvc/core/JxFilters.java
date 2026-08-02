/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jxmvc.core.JxLogger;

/**
 * Registro global de filtros / middlewares.
 *
 * <pre>
 *   // En un ServletContextListener o AppConfig:
 *
 *   // Logging
 *   JxFilters.add(ctx -> {
 *       System.out.printf("[%s] %s %s%n",
 *           ctx.ip(), ctx.method(), ctx.path());
 *       return true;
 *   });
 *
 *   // Auth Bearer Token
 *   JxFilters.add(ctx -> {
 *       if (!ctx.path().startsWith("/api/")) return true;
 *       String token = ctx.header("Authorization");
 *       if (token == null || !token.startsWith("Bearer ")) {
 *           ctx.response().status(401);
 *           ctx.response().json("{\"error\":\"token requerido\"}");
 *           return false;
 *       }
 *       return true;
 *   });
 *
 *   // Rate limiting, CORS personalizado, etc.
 * </pre>
 */
public final class JxFilters {

    private static final JxLogger      log     = JxLogger.getLogger(JxFilters.class);
    private static final List<JxFilter> filters = new CopyOnWriteArrayList<>();

    private JxFilters() {}

    /** Agrega un filtro al final de la cadena. */
    public static void add(JxFilter filter) { filters.add(filter); }

    /** Agrega un filtro en la posición indicada. */
    public static void add(int index, JxFilter filter) { filters.add(index, filter); }

    /** Elimina un filtro. */
    public static void remove(JxFilter filter) { filters.remove(filter); }

    /** Limpia todos los filtros. */
    public static void clear() { filters.clear(); }

    /** Número de filtros registrados. */
    public static int count() { return filters.size(); }

    /**
     * Ejecuta la cadena de filtros {@code before}.
     * @return {@code true} si todos los filtros permiten continuar
     */
    static boolean runBefore(JxFilterContext ctx) {
        for (JxFilter f : filters) {
            if (!f.before(ctx)) return false;
        }
        return true;
    }

    /** Ejecuta la cadena de filtros {@code after} (en orden inverso). */
    static void runAfter(JxFilterContext ctx) {
        for (int i = filters.size() - 1; i >= 0; i--) {
            try { filters.get(i).after(ctx); }
            catch (Exception e) {
                log.warn("JxFilter.after() falló: {}", e.getMessage());
            }
        }
    }
}
