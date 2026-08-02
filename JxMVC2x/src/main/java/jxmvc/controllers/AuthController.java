package jxmvc.controllers;

import jxmvc.auth.AppUser;
import jxmvc.auth.DemoAuth;
import jxmvc.core.ActionResult;
import jxmvc.core.JxEnvironment;
import jxmvc.core.JxLogger;
import jxmvc.core.JxOAuth;
import jxmvc.core.JxMapping.*;

/**
 * Autenticación de la aplicación demo: login nativo (correo + contraseña) y
 * "Iniciar sesión con Google" mediante {@link JxOAuth} del core. Muestra en un
 * caso real cómo cualquier app JxMVC integra ambos métodos sin dependencias externas.
 */
@JxControllerMapping("auth")
public class AuthController extends BaseController {

    private static final JxLogger log = JxLogger.getLogger(AuthController.class);

    private static final String SESSION_USER     = "user";
    private static final String SESSION_STATE    = "oauth.state";
    private static final String SESSION_VERIFIER = "oauth.verifier";

    // ── Login nativo ──────────────────────────────────────────────────────

    @JxGetMapping("login")
    public ActionResult loginForm() {
        if (currentUser() != null) return redirect("/auth/perfil");
        model.setVar("title",          "Iniciar sesión · JxMVC");
        model.setVar("googleEnabled",  JxOAuth.isGoogleConfigured());
        model.setVar("error",          model.param("error"));
        return view("auth/login");
    }

    @JxRateLimit(requests = 8, window = 60)
    @JxPostMapping("login")
    public ActionResult loginSubmit() {
        String email = model.param("email");
        String pass  = model.paramRaw("password");

        AppUser user = DemoAuth.authenticate(email, pass);
        if (user == null)
            return redirect("/auth/login?error=credenciales");

        sessionSet(SESSION_USER, user);
        return redirect("/auth/perfil");
    }

    // ── Login con Google (OAuth 2.0 + PKCE) ───────────────────────────────

    @JxGetMapping("google")
    public ActionResult google() {
        if (!JxOAuth.isGoogleConfigured())
            return redirect("/auth/login?error=google_off");

        JxOAuth.Flow flow = JxOAuth.google().start(redirectUri());
        sessionSet(SESSION_STATE,    flow.state());
        sessionSet(SESSION_VERIFIER, flow.codeVerifier());
        return external(flow.url());
    }

    @JxGetMapping("google/callback")
    public ActionResult googleCallback() {
        String error = model.param("error");
        if (error != null && !error.isBlank())
            return redirect("/auth/login?error=google_cancel");

        String state    = model.param("state");
        String expected = (String) sessionGet(SESSION_STATE);
        if (expected == null || !expected.equals(state))
            return redirect("/auth/login?error=state");

        try {
            String verifier = (String) sessionGet(SESSION_VERIFIER);
            JxOAuth.User g  = JxOAuth.google().login(model.param("code"), verifier, redirectUri());
            sessionSet(SESSION_USER, DemoAuth.fromGoogle(g));
        } catch (RuntimeException e) {
            log.warn("Fallo en el callback de Google: {}", e.getMessage());
            return redirect("/auth/login?error=google_fail");
        } finally {
            sessionSet(SESSION_STATE, null);
            sessionSet(SESSION_VERIFIER, null);
        }
        return redirect("/auth/perfil");
    }

    // ── Perfil protegido y logout ─────────────────────────────────────────

    @JxRequireAuth
    @JxGetMapping("perfil")
    public ActionResult perfil() {
        model.setVar("title", "Mi perfil · JxMVC");
        model.setVar("user",  currentUser());
        return view("auth/perfil");
    }

    @JxGetMapping("logout")
    public ActionResult logout() {
        sessionDestroy();
        return redirect("/");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private AppUser currentUser() {
        Object u = sessionGet(SESSION_USER);
        return u instanceof AppUser ? (AppUser) u : null;
    }

    /**
     * Redirección a una URL absoluta y de confianza (el proveedor OAuth). Se emite
     * a mano para no pasar por el prefijo de contexto ni por el bloqueo de
     * redirección externa que aplica a destinos derivados del usuario.
     */
    private ActionResult external(String url) {
        view.status(302);
        view.header("Location", url);
        return text("");
    }

    /**
     * URL absoluta del callback. Si {@code jxmvc.oauth.google.redirect-uri} está
     * configurada (recomendado en producción, detrás de Nginx/HTTPS) se usa tal cual;
     * de lo contrario se deriva de la petición (útil en local).
     */
    private String redirectUri() {
        String configured = JxEnvironment.get("jxmvc.oauth.google.redirect-uri", "");
        if (!configured.isBlank()) return configured;

        var req    = model.request;
        String scheme = headerOr(req.getHeader("X-Forwarded-Proto"), req.getScheme());
        String host   = headerOr(req.getHeader("X-Forwarded-Host"),  req.getHeader("Host"));
        if (host == null || host.isBlank()) host = req.getServerName() + ":" + req.getServerPort();
        return scheme + "://" + host + req.getContextPath() + "/auth/google/callback";
    }

    private static String headerOr(String header, String fallback) {
        return (header != null && !header.isBlank()) ? header.split(",")[0].trim() : fallback;
    }
}
