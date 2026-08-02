/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Proveedor de autenticación para la anotación {@code @JxAuth}.
 *
 * Implementar esta interfaz y registrarla en {@link JxSecurity}:
 * <pre>
 *   // En un ServletContextListener o similar:
 *   JxSecurity.setProvider((request, roles) -> {
 *       Object user = request.getSession(false) != null
 *           ? request.getSession().getAttribute("user")
 *           : null;
 *       return user != null;
 *   });
 * </pre>
 */
@FunctionalInterface
public interface JxAuthProvider {
    /**
     * @param request       petición HTTP actual
     * @param requiredRoles roles requeridos por la anotación (vacío = solo autenticado)
     * @return {@code true} si el acceso está permitido
     */
    boolean check(HttpServletRequest request, String[] requiredRoles);
}
