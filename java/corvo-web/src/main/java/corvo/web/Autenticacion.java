package corvo.web;

import corvo.core.Config;
import corvo.core.Context;
import corvo.core.OAuth;
import corvo.core.Passwords;
import corvo.core.Principal;
import corvo.http.Session;

/**
 * Quién puede entrar y cómo. Dos caminos: correo y contraseña contra un usuario de demostración,
 * o Google por OAuth 2.0 con PKCE.
 *
 * <p>El sitio corre sin base de datos, así que la credencial de demostración vive en memoria —
 * pero con la contraseña pasada por PBKDF2, igual que estaría en producción.
 */
public final class Autenticacion {

    static final String CLAVE_USUARIO = "usuario";
    static final String CLAVE_ESTADO = "oauth.estado";
    static final String CLAVE_VERIFICADOR = "oauth.verificador";

    private static final String CORREO_DEMO = "demo@luxcore.dev";
    private static final String NOMBRE_DEMO = "Usuario de demostración";
    private static final String HASH_DEMO = Passwords.hash("luxcore123");

    private final OAuth google;

    private Autenticacion(OAuth google) {
        this.google = google;
    }

    /** Lee la configuración de Google; sin credenciales el botón sale desactivado. */
    public static Autenticacion desde(Config config) {
        String id = config.get("lux.oauth.google.id", "");
        String secreto = config.get("lux.oauth.google.secreto", "");
        String redireccion = config.get("lux.oauth.google.redireccion",
                "http://localhost:8080/auth/google/callback");
        return new Autenticacion(id.isBlank() || secreto.isBlank()
                ? null
                : OAuth.google(id, secreto, redireccion));
    }

    public boolean googleDisponible() {
        return google != null;
    }

    public OAuth google() {
        if (google == null) {
            throw new IllegalStateException("Google no está configurado");
        }
        return google;
    }

    /** Valida correo y contraseña. Devuelve {@code null} si no coinciden. */
    public Usuario porContrasena(String correo, String contrasena) {
        if (correo == null || contrasena == null) {
            return null;
        }
        if (!CORREO_DEMO.equalsIgnoreCase(correo.trim()) || !Passwords.verify(contrasena, HASH_DEMO)) {
            return null;
        }
        return new Usuario("demo", CORREO_DEMO, NOMBRE_DEMO, null, "contraseña", "USER");
    }

    public Usuario desdeGoogle(OAuth.Identidad identidad) {
        String nombre = identidad.nombre() == null || identidad.nombre().isBlank()
                ? identidad.correo()
                : identidad.nombre();
        return new Usuario(identidad.sujeto(), identidad.correo(), nombre,
                identidad.foto(), "google", "USER");
    }

    /** Lo que LuxCore llama en cada petición para saber quién viene. */
    public Principal identificar(Context contexto) {
        Session sesion = contexto.session(false);
        return sesion != null && sesion.get(CLAVE_USUARIO) instanceof Usuario usuario
                ? usuario
                : null;
    }

    static Usuario actual(Context contexto) {
        Session sesion = contexto.session(false);
        return sesion != null && sesion.get(CLAVE_USUARIO) instanceof Usuario usuario
                ? usuario
                : null;
    }
}
