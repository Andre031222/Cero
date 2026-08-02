package jxmvc.auth;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jxmvc.core.JxLogger;
import jxmvc.core.JxOAuth;
import jxmvc.core.JxProfile;
import jxmvc.core.JxSecurity;

/**
 * Arranque de la aplicación: registra el proveedor de autenticación de JxMVC.
 * A partir de aquí {@code @JxRequireAuth} y {@code @JxRequireRole} quedan activos
 * y validan contra el {@link AppUser} guardado en la sesión.
 */
@WebListener
public class AppBootstrap implements ServletContextListener {

    private static final JxLogger log = JxLogger.getLogger(AppBootstrap.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        JxSecurity.setProvider(AppBootstrap::authorize);

        boolean prod = JxProfile.isProd();
        try {
            sce.getServletContext().getSessionCookieConfig().setSecure(prod);
        } catch (IllegalStateException e) {
            log.warn("No se pudo ajustar el flag Secure de la cookie de sesión: {}", e.getMessage());
        }

        log.info("Auth lista — proveedor de sesión registrado (cookie Secure={}). Google OAuth: {}",
                prod, JxOAuth.isGoogleConfigured() ? "configurado" : "sin configurar");
    }

    private static boolean authorize(HttpServletRequest request, String[] requiredRoles) {
        HttpSession session = request.getSession(false);
        Object principal = session != null ? session.getAttribute("user") : null;
        if (!(principal instanceof AppUser user)) return false;
        if (requiredRoles == null || requiredRoles.length == 0) return true;
        for (String role : requiredRoles) {
            if (user.hasRole(role)) return true;
        }
        return false;
    }
}
