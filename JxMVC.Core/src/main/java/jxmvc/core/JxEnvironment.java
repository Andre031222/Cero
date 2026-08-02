/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

/**
 * Acceso centralizado a todas las propiedades de {@code application.properties}.
 *
 * <pre>
 *   String  host = JxEnvironment.get("mail.host", "localhost");
 *   int     port = JxEnvironment.getInt("mail.port", 25);
 *   boolean ssl  = JxEnvironment.getBoolean("mail.ssl", false);
 *   boolean dev  = JxEnvironment.is("app.env", "development");
 * </pre>
 *
 * Para inyección directa en servicios, usar {@code @JxValue("${clave:default}")}.
 */
public final class JxEnvironment {

    private JxEnvironment() {}

    // ── Acceso tipado ─────────────────────────────────────────────────────

    /** Retorna el valor de la propiedad o {@code null} si no existe. */
    public static String get(String key) {
        return BaseDbResolver.property(key, null);
    }

    /** Retorna el valor de la propiedad o {@code defaultValue} si no existe. */
    public static String get(String key, String defaultValue) {
        return BaseDbResolver.property(key, defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        String v = get(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public static long getLong(String key, long defaultValue) {
        String v = get(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public static double getDouble(String key, double defaultValue) {
        String v = get(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key);
        if (v == null || v.isBlank()) return defaultValue;
        return Boolean.parseBoolean(v.trim());
    }

    /** Retorna {@code true} si la propiedad existe (aunque sea vacía). */
    public static boolean has(String key) {
        return get(key) != null;
    }

    /** Retorna {@code true} si el valor de la propiedad es igual a {@code expected} (ignore case). */
    public static boolean is(String key, String expected) {
        String v = get(key);
        return v != null && v.trim().equalsIgnoreCase(expected);
    }

    /** Retorna {@code true} si estamos en modo de desarrollo ({@code app.env=development} o {@code jxmvc.dev=true}). */
    public static boolean isDev() {
        return is("app.env", "development") || is("app.env", "dev")
            || getBoolean("jxmvc.dev", false);
    }
}
