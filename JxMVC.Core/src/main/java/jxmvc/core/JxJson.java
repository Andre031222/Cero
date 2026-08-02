/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializador JSON ligero — cero dependencias externas.
 * Soporta: null, String, Number, Boolean, DBRow, DBRowSet,
 *          List, Map y cualquier POJO con getters públicos.
 *
 * <pre>
 *   String json = JxJson.toJson(dbRowSet);
 *   String json = JxJson.toJson(Map.of("ok", true, "count", 5));
 * </pre>
 */
public final class JxJson {

    private static final JxLogger log = JxLogger.getLogger(JxJson.class);

    private JxJson() {}

    // ── Deserialización ───────────────────────────────────────────────────

    /**
     * Deserializa JSON a un POJO, tipo primitivo boxeado, {@link Map} o {@link List}.
     *
     * <pre>
     *   UserDto dto  = JxJson.fromJson(body, UserDto.class);
     *   List&lt;?&gt; list = JxJson.fromJson(body, List.class);
     * </pre>
     */
    private static final int MAX_JSON_BYTES = 10 * 1024 * 1024; // 10 MB

    @SuppressWarnings("unchecked")
    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        if (json.length() > MAX_JSON_BYTES)
            throw new JxException(400, "JSON demasiado grande (máx 10 MB)");
        Object parsed = new JsonParser(json.trim()).parse();
        if (parsed == null) return null;

        if (type == String.class)
            return type.cast(parsed instanceof String ? parsed : String.valueOf(parsed));
        if (type == Integer.class || type == int.class)
            return (T) Integer.valueOf(((Number) parsed).intValue());
        if (type == Long.class    || type == long.class)
            return (T) Long.valueOf(((Number) parsed).longValue());
        if (type == Double.class  || type == double.class)
            return (T) Double.valueOf(((Number) parsed).doubleValue());
        if (type == Float.class   || type == float.class)
            return (T) Float.valueOf(((Number) parsed).floatValue());
        if (type == Boolean.class || type == boolean.class)
            return (T) Boolean.valueOf(String.valueOf(parsed));
        if (type == Map.class  || type == LinkedHashMap.class)
            return (T) parsed;
        if (type == List.class || type == ArrayList.class)
            return (T) parsed;
        if (parsed instanceof Map<?, ?> map)
            return mapToPojo((Map<String, Object>) map, type);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapToPojo(Map<String, Object> map, Class<T> type) {
        try {
            T obj = type.getDeclaredConstructor().newInstance();
            for (Field f : collectPojoFields(type)) {
                Object raw = map.get(f.getName());
                if (raw == null) continue;
                f.setAccessible(true);
                f.set(obj, coerce(raw, f.getType()));
            }
            return obj;
        } catch (Exception e) {
            log.debug("No se pudo mapear JSON a {}: {}", type.getName(), e.getMessage());
            return null;
        }
    }

    private static Object coerce(Object value, Class<?> type) {
        if (value == null) return null;
        if (type.isInstance(value)) return value;
        if (type == String.class)                                  return String.valueOf(value);
        if ((type == int.class     || type == Integer.class) && value instanceof Number n) return n.intValue();
        if ((type == long.class    || type == Long.class)    && value instanceof Number n) return n.longValue();
        if ((type == double.class  || type == Double.class)  && value instanceof Number n) return n.doubleValue();
        if ((type == float.class   || type == Float.class)   && value instanceof Number n) return n.floatValue();
        if ((type == boolean.class || type == Boolean.class) && value instanceof Boolean b) return b;
        if (value instanceof String s) {
            String t = s.trim();
            try {
                if (type == int.class       || type == Integer.class)   return Integer.parseInt(t);
                if (type == long.class      || type == Long.class)      return Long.parseLong(t);
                if (type == double.class    || type == Double.class)    return Double.parseDouble(t);
                if (type == boolean.class   || type == Boolean.class)   return Boolean.parseBoolean(t);
                if (type == LocalDate.class)                            return LocalDate.parse(t);
                if (type == LocalDateTime.class)                        return LocalDateTime.parse(t);
                if (type == LocalTime.class)                            return LocalTime.parse(t);
            } catch (Exception ignored) {}
        }
        return value;
    }

    private static List<Field> collectPojoFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (isBindable(f)) fields.add(f);
            }
            cls = cls.getSuperclass();
        }
        return fields;
    }

    private static boolean isBindable(Field f) {
        int m = f.getModifiers();
        return !java.lang.reflect.Modifier.isStatic(m)
            && !java.lang.reflect.Modifier.isFinal(m)
            && !java.lang.reflect.Modifier.isTransient(m)
            && !f.isSynthetic();
    }

    // ── Parser JSON interno ────────────────────────────────────────────────

    private static final class JsonParser {
        private static final int MAX_DEPTH = 64;
        private final String src;
        private int pos;
        private int depth;

        JsonParser(String src) { this.src = src; }

        Object parse() {
            skipWs();
            if (pos >= src.length()) return null;
            return switch (src.charAt(pos)) {
                case '{'  -> parseObject();
                case '['  -> parseArray();
                case '"'  -> parseString();
                case 't', 'f' -> parseBool();
                case 'n'  -> { pos += 4; yield null; }
                default   -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            if (++depth > MAX_DEPTH) throw new JxException(400, "JSON demasiado anidado");
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;  // '{'
            skipWs();
            while (pos < src.length() && src.charAt(pos) != '}') {
                skipWs();
                if (src.charAt(pos) == '"') {
                    String key = parseString();
                    skipWs();
                    if (pos < src.length() && src.charAt(pos) == ':') pos++;
                    skipWs();
                    map.put(key, parse());
                }
                skipWs();
                if (pos < src.length() && src.charAt(pos) == ',') pos++;
                skipWs();
            }
            if (pos < src.length()) pos++;  // '}'
            depth--;
            return map;
        }

        private List<Object> parseArray() {
            if (++depth > MAX_DEPTH) throw new JxException(400, "JSON demasiado anidado");
            List<Object> list = new ArrayList<>();
            pos++;  // '['
            skipWs();
            while (pos < src.length() && src.charAt(pos) != ']') {
                list.add(parse());
                skipWs();
                if (pos < src.length() && src.charAt(pos) == ',') pos++;
                skipWs();
            }
            if (pos < src.length()) pos++;  // ']'
            depth--;
            return list;
        }

        private String parseString() {
            pos++;  // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            if (pos + 4 <= src.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                                    pos += 4;
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                        default   -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private boolean parseBool() {
            if (src.startsWith("true", pos))  { pos += 4; return true; }
            pos += 5;  // "false"
            return false;
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
                pos++;
            }
            String num = src.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                try { return Double.parseDouble(num); } catch (NumberFormatException ignored) {}
            }
            try { return Long.parseLong(num); }
            catch (NumberFormatException e) {
                try { return Double.parseDouble(num); } catch (NumberFormatException e2) { return 0; }
            }
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }

    // ── Serialización ─────────────────────────────────────────────────────

    public static String toJson(Object value) {
        if (value == null)                          return "null";
        if (value instanceof String s)              return quote(s);
        if (value instanceof Number)                return value.toString();
        if (value instanceof Boolean)               return value.toString();
        if (value instanceof LocalDate d)           return quote(d.toString());
        if (value instanceof LocalDateTime dt)      return quote(dt.toString());
        if (value instanceof LocalTime t)           return quote(t.toString());
        if (value instanceof java.sql.Date d)       return quote(d.toLocalDate().toString());
        if (value instanceof java.sql.Timestamp ts) return quote(ts.toLocalDateTime().toString());
        if (value instanceof java.util.Date d)      return quote(new java.sql.Timestamp(d.getTime()).toLocalDateTime().toString());
        if (value instanceof DBRow row)             return rowToJson(row);
        if (value instanceof DBRowSet rs)           return rowSetToJson(rs);
        if (value instanceof List<?> list)          return listToJson(list);
        if (value instanceof Map<?, ?> map)         return mapToJson(map);
        return reflectToJson(value);
    }

    // ── Internos ──────────────────────────────────────────────────────────

    static String quote(String s) {
        return "\"" + escape(s) + "\"";
    }

    /**
     * Escapa una cadena para insertarla en JSON de forma segura.
     * Además de los escapes estándar, neutraliza {@code <} y {@code >} (evita romper
     * el contexto {@code </script>} cuando el JSON se incrusta en HTML), los separadores
     * de línea U+2028/U+2029 (que rompen literales JS) y todo carácter de control.
     */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '<'  -> sb.append("\\u003c");
                case '>'  -> sb.append("\\u003e");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029)
                        sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static String rowToJson(DBRow row) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : row.keySet()) {
            if (!first) sb.append(',');
            sb.append(quote(k)).append(':').append(toJson(row.get(k)));
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String rowSetToJson(DBRowSet rs) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DBRow row : rs.result()) {
            if (!first) sb.append(',');
            sb.append(rowToJson(row));
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String listToJson(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object item : list) {
            if (!first) sb.append(',');
            sb.append(toJson(item));
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String mapToJson(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            sb.append(quote(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String reflectToJson(Object obj) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
            String name = m.getName();
            String key  = null;
            if (name.startsWith("get") && name.length() > 3 && !name.equals("getClass")) {
                key = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            } else if (name.startsWith("is") && name.length() > 2) {
                key = Character.toLowerCase(name.charAt(2)) + name.substring(3);
            }
            if (key == null) continue;
            try {
                if (!first) sb.append(',');
                sb.append(quote(key)).append(':').append(toJson(m.invoke(obj)));
                first = false;
            } catch (Exception ignored) {}
        }
        return sb.append('}').toString();
    }
}
