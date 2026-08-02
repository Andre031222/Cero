/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

/**
 * Filtro / middleware que se ejecuta antes y después de cada acción.
 *
 * <pre>
 *   // Registrar en un ServletContextListener o bloque estático:
 *   JxFilters.add(new JxFilter() {
 *       public boolean before(JxFilterContext ctx) {
 *           System.out.println("→ " + ctx.method() + " " + ctx.path());
 *           return true;  // continuar
 *       }
 *       public void after(JxFilterContext ctx) {
 *           System.out.println("← " + ctx.status());
 *       }
 *   });
 *
 *   // Filtro de autenticación:
 *   JxFilters.add(ctx -> {
 *       if (ctx.path().startsWith("/api/") && ctx.session("user") == null) {
 *           ctx.response().status(401);
 *           ctx.response().json("{\"error\":\"no autorizado\"}");
 *           return false;  // detener cadena
 *       }
 *       return true;
 *   });
 * </pre>
 */
@FunctionalInterface
public interface JxFilter {

    /**
     * Se ejecuta ANTES de invocar la acción del controlador.
     *
     * @param ctx contexto con request, response, ruta, controller y action
     * @return {@code true} para continuar la cadena, {@code false} para detenerla
     *         (el filtro debe haber escrito la respuesta antes de retornar false)
     */
    boolean before(JxFilterContext ctx);

    /**
     * Se ejecuta DESPUÉS de invocar la acción del controlador.
     * Por defecto no hace nada.
     */
    default void after(JxFilterContext ctx) {}
}
