package jxmvc.auth;

import jxmvc.core.JxOAuth;
import jxmvc.core.JxPasswords;

/**
 * Almacén de usuarios de demostración para el login nativo (correo + contraseña).
 * El sitio corre sin base de datos, así que la credencial vive en memoria con la
 * contraseña hasheada por {@link JxPasswords} (PBKDF2), igual que en producción.
 *
 * <p>Credencial de demo: <b>demo@jxmvc.dev</b> / <b>jxmvc123</b>.
 */
public final class DemoAuth {

    private static final String DEMO_EMAIL = "demo@jxmvc.dev";
    private static final String DEMO_NAME  = "Usuario Demo";
    private static final String DEMO_HASH  = JxPasswords.hash("jxmvc123");

    private DemoAuth() {}

    /** Valida correo + contraseña; retorna el usuario o {@code null} si no coincide. */
    public static AppUser authenticate(String email, String password) {
        if (email == null || password == null) return null;
        if (!DEMO_EMAIL.equalsIgnoreCase(email.trim())) return null;
        if (!JxPasswords.verify(password, DEMO_HASH))    return null;
        return new AppUser("demo", DEMO_EMAIL, DEMO_NAME, null, "password", "USER");
    }

    /** Convierte la identidad devuelta por Google en un usuario de la aplicación. */
    public static AppUser fromGoogle(JxOAuth.User g) {
        String name = (g.name() != null && !g.name().isBlank()) ? g.name() : g.email();
        return new AppUser(g.id(), g.email(), name, g.picture(), "google", "USER");
    }
}
