/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Resuelve y aplica la política CORS por controlador o método.
 * Sin anotación {@code @JxCors}: peticiones cross-origin son bloqueadas.
 * Peticiones same-origin (sin cabecera Origin) siempre pasan.
 */
public final class BaseCorsResolver {

    private static final String[] DEFAULT_METHODS  = {"GET","POST","PUT","DELETE","PATCH","OPTIONS"};
    private static final String[] DEFAULT_ORIGINS  = {};
    private static final String[] DEFAULT_HOSTS    = {};

    public boolean applyAndCheck(
            HttpServletRequest req, HttpServletResponse resp,
            Class<?> controllerClass, Method actionMethod) {

        JxMapping.JxCors methodPolicy     = actionMethod.getAnnotation(JxMapping.JxCors.class);
        JxMapping.JxCors controllerPolicy = controllerClass.getAnnotation(JxMapping.JxCors.class);
        boolean hasAnnotation              = methodPolicy != null || controllerPolicy != null;

        String origin = safe(req.getHeader("Origin"));

        if (origin.isBlank()) return true;   // same-origin sin cabecera, siempre OK
        if (isSameHost(req, origin)) {       // mismo host:puerto → same-origin
            // La respuesta depende de Origin: que las cachés intermedias no la reutilicen cross-origin.
            resp.addHeader("Vary", "Origin");
            return true;
        }
        if (!hasAnnotation)   return false;  // cross-origin sin política → bloquear

        CorsPolicy policy = resolvePolicy(methodPolicy, controllerPolicy);
        String method = requestedMethod(req);
        String host   = safe(req.getRemoteAddr());

        if (!matchesMethod(policy.methods(), method))                        return false;
        if (isProvided(policy.hosts())   && !matches(policy.hosts(), host))  return false;
        if (!origin.isBlank()            && !matches(policy.origins(), origin)) return false;

        applyHeaders(req, resp, policy, origin);
        return true;
    }

    private CorsPolicy resolvePolicy(JxMapping.JxCors method, JxMapping.JxCors ctrl) {
        String[] origins = pick(method != null ? method.origins() : null,
                                ctrl   != null ? ctrl.origins()   : null, DEFAULT_ORIGINS);
        String[] hosts   = pick(method != null ? method.hosts()   : null,
                                ctrl   != null ? ctrl.hosts()     : null, DEFAULT_HOSTS);
        String[] methods = pick(method != null ? method.methods() : null,
                                ctrl   != null ? ctrl.methods()   : null, DEFAULT_METHODS);
        boolean creds = method != null ? method.allowCredentials() : ctrl != null && ctrl.allowCredentials();
        long maxAge   = method != null ? method.maxAge() : ctrl != null ? ctrl.maxAge() : 3600L;
        return new CorsPolicy(origins, hosts, methods, creds, maxAge);
    }

    private void applyHeaders(HttpServletRequest req, HttpServletResponse resp,
                               CorsPolicy policy, String origin) {
        if (policy.allowCredentials() && containsWildcard(policy.origins())) return;

        String allowOrigin = "*";
        if (!origin.isBlank() && (policy.allowCredentials() || !containsWildcard(policy.origins()))) {
            allowOrigin = origin;
        }
        resp.setHeader("Access-Control-Allow-Origin",  allowOrigin);
        resp.setHeader("Vary",                         "Origin");
        resp.setHeader("Access-Control-Allow-Methods", String.join(", ", policy.methods()));

        String reqHeaders = req.getHeader("Access-Control-Request-Headers");
        if (reqHeaders == null || reqHeaders.isBlank())
            reqHeaders = "Content-Type, Authorization, X-Requested-With";
        resp.setHeader("Access-Control-Allow-Headers", reqHeaders);
        resp.setHeader("Access-Control-Max-Age",       String.valueOf(policy.maxAge()));
        if (policy.allowCredentials())
            resp.setHeader("Access-Control-Allow-Credentials", "true");
    }

    private String requestedMethod(HttpServletRequest req) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            String r = safe(req.getHeader("Access-Control-Request-Method"));
            if (!r.isBlank()) return r;
        }
        return safe(req.getMethod());
    }

    private String[] pick(String[] m, String[] c, String[] def) {
        if (isProvided(m)) return m;
        if (isProvided(c)) return c;
        return def;
    }

    private boolean isProvided(String[] v)  { return v != null && v.length > 0; }
    private boolean containsWildcard(String[] v) { return v != null && Arrays.stream(v).anyMatch(x -> "*".equals(safe(x))); }

    private boolean matches(String[] allowed, String input) {
        if (allowed == null || allowed.length == 0) return false;
        String in = safe(input);
        return Arrays.stream(allowed).map(this::safe).anyMatch(a -> "*".equals(a) || a.equalsIgnoreCase(in));
    }

    private boolean matchesMethod(String[] allowed, String method) {
        if ("OPTIONS".equalsIgnoreCase(method)) {
            if (containsWildcard(allowed)) return true;
            return Arrays.stream(allowed).map(this::safe)
                    .anyMatch(v -> "OPTIONS".equalsIgnoreCase(v) || "GET".equalsIgnoreCase(v)
                                || "POST".equalsIgnoreCase(v) || "PUT".equalsIgnoreCase(v)
                                || "DELETE".equalsIgnoreCase(v) || "PATCH".equalsIgnoreCase(v));
        }
        return matches(allowed, method);
    }

    /**
     * True si el Origin apunta al mismo host que el servidor.
     * Compara solo la parte host (ignorando esquema y puerto) para funcionar
     * correctamente detrás de un reverse proxy (Apache/Nginx) donde el esquema
     * interno es http:8080 pero el Origin llega como https:443.
     * ProxyPreserveHost On garantiza que req.getServerName() == host público.
     */
    private boolean isSameHost(HttpServletRequest req, String origin) {
        if (origin.isBlank()) return true;
        try {
            // Extraer solo el host del Origin (sin esquema ni puerto)
            String originHost = origin
                    .replaceFirst("^https?://", "")
                    .replaceFirst(":\\d+$", "")
                    .trim();
            // req.getServerName() devuelve el Host header (preservado por ProxyPreserveHost)
            String serverName = safe(req.getServerName());
            return !originHost.isEmpty() && originHost.equalsIgnoreCase(serverName);
        } catch (Exception e) {
            return false;
        }
    }

    private String safe(String v) { return v == null ? "" : v.trim(); }

    private record CorsPolicy(
        String[] origins, String[] hosts, String[] methods,
        boolean allowCredentials, long maxAge) {}
}
