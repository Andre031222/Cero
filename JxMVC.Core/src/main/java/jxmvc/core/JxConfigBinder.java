/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.lang.reflect.Field;

/**
 * Vincula propiedades de {@code application.properties} a un POJO anotado con
 * {@link JxMapping.JxConfigProperties}.
 *
 * <pre>
 *   &#64;JxConfigProperties("mail")
 *   &#64;JxService
 *   public class MailConfig {
 *       public String host;          // mail.host
 *       public int    port  = 25;    // mail.port  (25 = default si no existe)
 *       public String from;          // mail.from
 *       public boolean tls = false;  // mail.tls
 *   }
 *
 *   // Obtener manualmente:
 *   MailConfig cfg = JxConfigBinder.bind(MailConfig.class);
 *
 *   // O vía DI (si @JxService):
 *   &#64;JxInject MailConfig mailConfig;
 * </pre>
 *
 * Soporta tipos: {@code String}, {@code int}/{@code Integer}, {@code long}/{@code Long},
 * {@code double}/{@code Double}, {@code float}/{@code Float}, {@code boolean}/{@code Boolean}.
 */
public final class JxConfigBinder {

    private JxConfigBinder() {}

    /**
     * Crea una instancia de {@code cls} y rellena sus campos con las propiedades
     * que coincidan con el prefijo definido en {@link JxMapping.JxConfigProperties}.
     *
     * @throws IllegalArgumentException si {@code cls} no tiene {@code @JxConfigProperties}
     */
    public static <T> T bind(Class<T> cls) {
        JxMapping.JxConfigProperties ann = cls.getAnnotation(JxMapping.JxConfigProperties.class);
        if (ann == null)
            throw new IllegalArgumentException(cls.getName() + " no tiene @JxConfigProperties");
        return bind(cls, ann.value());
    }

    /**
     * Crea una instancia de {@code cls} y rellena sus campos con las propiedades
     * que empiecen con {@code prefix}.
     */
    public static <T> T bind(Class<T> cls, String prefix) {
        T instance;
        try {
            instance = cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new JxException(500, "No se puede instanciar " + cls.getName() + ": " + e.getMessage());
        }

        String pfx = (prefix == null || prefix.isBlank()) ? "" : prefix.trim() + ".";

        for (Field f : collectFields(cls)) {
            f.setAccessible(true);
            String key = pfx + f.getName();
            // Convierte camelCase a kebab-case como alternativa (ej. mailHost → mail.mail-host)
            String keyKebab = pfx + toKebab(f.getName());

            String raw = BaseDbResolver.property(key, null);
            if (raw == null) raw = BaseDbResolver.property(keyKebab, null);
            if (raw == null) continue;   // usar el valor por defecto del campo

            try {
                f.set(instance, coerce(raw.trim(), f.getType()));
            } catch (IllegalAccessException e) {
                JxLogger.getLogger(JxConfigBinder.class)
                        .warn("@JxConfigProperties falló en {}.{}: {}", cls.getSimpleName(), f.getName(), e.getMessage());
            }
        }

        return instance;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static Object coerce(String val, Class<?> type) {
        if (type == String.class)                              return val;
        if (type == int.class     || type == Integer.class)    { try { return Integer.parseInt(val); }   catch (Exception e) { return 0; } }
        if (type == long.class    || type == Long.class)       { try { return Long.parseLong(val); }     catch (Exception e) { return 0L; } }
        if (type == double.class  || type == Double.class)     { try { return Double.parseDouble(val); } catch (Exception e) { return 0.0; } }
        if (type == float.class   || type == Float.class)      { try { return Float.parseFloat(val); }   catch (Exception e) { return 0f; } }
        if (type == boolean.class || type == Boolean.class)    return Boolean.parseBoolean(val);
        return val;
    }

    private static java.util.List<Field> collectFields(Class<?> cls) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) fields.add(f);
            cls = cls.getSuperclass();
        }
        return fields;
    }

    /** {@code mailHost} → {@code mail-host} */
    private static String toKebab(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) { sb.append('-'); sb.append(Character.toLowerCase(c)); }
            else sb.append(c);
        }
        return sb.toString();
    }
}
