/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  improved : R. Andre Vilca Solorzano

package jxmvc.core;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gestión de perfiles de ejecución — equivalente de {@code spring.profiles.active}.
 *
 * <p>Configurar en {@code application.properties}:
 * <pre>
 *   jxmvc.profiles.active=prod
 *   # o múltiples:
 *   jxmvc.profiles.active=prod,metrics
 * </pre>
 *
 * <p>O por variable de entorno / propiedad del sistema (mayor precedencia):
 * <pre>
 *   -Djxmvc.profiles.active=dev
 *   export JXMVC_PROFILE=dev
 * </pre>
 *
 * <p>Uso en código:
 * <pre>
 *   if (JxProfile.is("dev")) {
 *       // habilitar debug, datos semilla…
 *   }
 *
 *   // Servicio activo solo en prod
 *   &#64;JxService
 *   &#64;JxActiveProfile("prod")
 *   public class SentryService { ... }
 * </pre>
 */
public final class JxProfile {

    private static final Set<String> ACTIVE;

    static {
        // Precedencia: sistema → entorno → properties.
        // Clave canónica jxmvc.profiles.active; se acepta el alias legado
        // jxmvc.profile (singular) para no fallar en silencio si se usa esa.
        String raw = System.getProperty("jxmvc.profiles.active");
        if (raw == null) raw = System.getProperty("jxmvc.profile");
        if (raw == null) raw = System.getenv("JXMVC_PROFILE");
        if (raw == null) raw = BaseDbResolver.property("jxmvc.profiles.active", "");
        if (raw.isBlank()) raw = BaseDbResolver.property("jxmvc.profile", "default");
        ACTIVE = Arrays.stream(raw.split(","))
                       .map(s -> s.trim().toLowerCase())
                       .filter(s -> !s.isBlank())
                       .collect(Collectors.toUnmodifiableSet());
    }

    private JxProfile() {}

    /** Retorna el primer perfil activo (o {@code "default"}). */
    public static String active() {
        return ACTIVE.isEmpty() ? "default" : ACTIVE.iterator().next();
    }

    /** Retorna todos los perfiles activos. */
    public static Set<String> activeProfiles() { return ACTIVE; }

    /**
     * {@code true} si el perfil dado está activo.
     *
     * <pre>
     *   JxProfile.is("dev")   // true en dev, false en prod
     * </pre>
     */
    public static boolean is(String profile) {
        return ACTIVE.contains(profile.trim().toLowerCase());
    }

    /**
     * {@code true} si ALGUNO de los perfiles dados está activo.
     *
     * <pre>
     *   JxProfile.isAny("dev", "staging")
     * </pre>
     */
    public static boolean isAny(String... profiles) {
        for (String p : profiles) { if (is(p)) return true; }
        return false;
    }

    /** {@code true} si TODOS los perfiles dados están activos. */
    public static boolean isAll(String... profiles) {
        for (String p : profiles) { if (!is(p)) return false; }
        return true;
    }

    /** {@code true} si no es producción — útil para habilitar datos de prueba. */
    public static boolean isDev()  { return is("dev")  || is("development") || is("default"); }
    public static boolean isProd() { return is("prod") || is("production"); }
    public static boolean isTest() { return is("test"); }
}
