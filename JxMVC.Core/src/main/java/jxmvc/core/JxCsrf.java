/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Protección CSRF por token de sincronización (patrón <i>synchronizer token</i>).
 *
 * <p>Se activa con {@code jxmvc.security.csrf=true}. Con la protección activa,
 * toda petición con verbo mutador (POST/PUT/DELETE/PATCH) debe presentar el token
 * de la sesión en el campo de formulario {@code _csrf} o en la cabecera
 * {@code X-CSRF-Token}; de lo contrario responde 403. Las acciones anotadas con
 * {@code @JxCsrfExempt} (p. ej. APIs consumidas por otros servicios) quedan fuera.
 *
 * <p>En JSP el token se emite con la función EL {@code jx:csrf}:
 * <pre>
 *   &lt;form method="post" action="..."&gt;
 *     ${jx:csrf(pageContext.request)}
 *     ...
 *   &lt;/form&gt;
 * </pre>
 * que imprime {@code <input type="hidden" name="_csrf" value="…">}. Para fetch/AJAX
 * se envía el mismo valor en la cabecera {@code X-CSRF-Token} (disponible con
 * {@code jx:csrfToken(pageContext.request)}).
 */
public final class JxCsrf {

    /** Nombre del campo de formulario y de la cabecera aceptados. */
    public static final String FIELD  = "_csrf";
    public static final String HEADER = "X-CSRF-Token";

    static final String SESSION_KEY = "jx.csrf.token";

    private static final boolean ENABLED =
            BaseDbResolver.propertyBool("jxmvc.security.csrf", false);

    private static final SecureRandom RANDOM = new SecureRandom();

    private JxCsrf() {}

    static boolean enabled() { return ENABLED; }

    /** Token de la sesión, creándolo si no existe. */
    public static String token(HttpServletRequest req) {
        HttpSession session = req.getSession(true);
        synchronized (session) {
            String token = (String) session.getAttribute(SESSION_KEY);
            if (token == null) {
                byte[] bytes = new byte[32];
                RANDOM.nextBytes(bytes);
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                session.setAttribute(SESSION_KEY, token);
            }
            return token;
        }
    }

    /** Campo oculto listo para insertar dentro de un {@code <form>} (función EL {@code jx:csrf}). */
    public static String field(HttpServletRequest req) {
        return "<input type=\"hidden\" name=\"" + FIELD + "\" value=\"" + token(req) + "\">";
    }

    /**
     * Valida la petición: los verbos seguros pasan siempre; los mutadores requieren
     * token coincidente (comparación en tiempo constante). Sin sesión previa no hay
     * token emitido, por lo que la petición mutadora se rechaza.
     */
    static boolean validate(HttpServletRequest req) {
        String verb = req.getMethod().toUpperCase();
        if ("GET".equals(verb) || "HEAD".equals(verb) || "OPTIONS".equals(verb) || "TRACE".equals(verb))
            return true;

        HttpSession session = req.getSession(false);
        String expected = session == null ? null : (String) session.getAttribute(SESSION_KEY);
        if (expected == null) return false;

        String presented = req.getHeader(HEADER);
        if (presented == null || presented.isBlank()) presented = req.getParameter(FIELD);
        if (presented == null || presented.isBlank()) return false;

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
