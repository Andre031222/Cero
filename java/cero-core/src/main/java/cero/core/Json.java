package cero.core;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        var out = new StringBuilder(128);
        new Writer(out).value(value);
        return out.toString();
    }

    public static Object read(String text) {
        return new Parser(text).parseDocument();
    }

    public static <T> T read(String text, Class<T> type) {
        return bind(read(text), type);
    }

    @SuppressWarnings("unchecked")
    public static <T> T bind(Object tree, Class<T> type) {
        return (T) Convert.to(tree, type, null);
    }

    private static final class Writer {

        private final StringBuilder out;
        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        Writer(StringBuilder out) {
            this.out = out;
        }

        void value(Object source) {
            switch (source) {
                case null -> out.append("null");
                case String text -> string(text);
                case Character letter -> string(letter.toString());
                case Boolean flag -> out.append(flag);
                case Double number -> out.append(number.isNaN() || number.isInfinite() ? "null" : number.toString());
                case Float number -> out.append(number.isNaN() || number.isInfinite() ? "null" : number.toString());
                case Number number -> out.append(number);
                case Enum<?> constant -> string(constant.name());
                case Optional<?> maybe -> value(maybe.orElse(null));
                case UUID id -> string(id.toString());
                case Temporal moment -> string(moment.toString());
                case Timestamp instante -> string(instante.toInstant().toString());
                case Time reloj -> string(reloj.toString());
                case java.sql.Date dia -> string(dia.toLocalDate().toString());
                case Date moment -> string(Instant.ofEpochMilli(moment.getTime()).toString());
                case Map<?, ?> map -> object(map);
                case Collection<?> items -> array(items);
                case Object any when any.getClass().isArray() -> nativeArray(any);
                case Object any when any instanceof Record -> record(any);
                default -> bean(source);
            }
        }

        private void object(Map<?, ?> map) {
            enter(map);
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(String.valueOf(entry.getKey()));
                out.append(':');
                value(entry.getValue());
            }
            out.append('}');
            leave(map);
        }

        private void array(Collection<?> items) {
            enter(items);
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                value(item);
            }
            out.append(']');
            leave(items);
        }

        private void nativeArray(Object source) {
            enter(source);
            out.append('[');
            int length = Array.getLength(source);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                value(Array.get(source, i));
            }
            out.append(']');
            leave(source);
        }

        private void record(Object source) {
            enter(source);
            out.append('{');
            RecordComponent[] components = source.getClass().getRecordComponents();
            for (int i = 0; i < components.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                string(components[i].getName());
                out.append(':');
                value(invoke(components[i].getAccessor(), source));
            }
            out.append('}');
            leave(source);
        }

        private void bean(Object source) {
            enter(source);
            out.append('{');
            boolean first = true;
            for (Method method : source.getClass().getMethods()) {
                String name = propertyName(method);
                if (name == null) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(name);
                out.append(':');
                value(invoke(method, source));
            }
            out.append('}');
            leave(source);
        }

        private void enter(Object source) {
            if (!seen.add(source)) {
                throw new IllegalArgumentException("ciclo al serializar " + source.getClass().getName());
            }
        }

        private void leave(Object source) {
            seen.remove(source);
        }

        private void string(String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    default -> {
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }

        private static String propertyName(Method method) {
            if (method.getParameterCount() > 0 || method.getDeclaringClass() == Object.class) {
                return null;
            }
            String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                return decapitalize(name.substring(3));
            }
            if (name.startsWith("is") && name.length() > 2
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                return decapitalize(name.substring(2));
            }
            return null;
        }

        private static String decapitalize(String name) {
            if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
                return name;
            }
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }

        private static Object invoke(Method method, Object target) {
            try {
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException cause) {
                throw new IllegalStateException("no se pudo leer " + method.getName(), cause);
            }
        }
    }

    private static final class Parser {

        private static final int MAX_PROFUNDIDAD = 64;

        private final String text;
        private int at;
        private int profundidad;

        Parser(String text) {
            this.text = text;
        }

        Object parseDocument() {
            Object value = parseValue();
            skipWhitespace();
            if (at < text.length()) {
                throw error("contenido sobrante");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (at >= text.length()) {
                throw error("documento vacío");
            }
            return switch (text.charAt(at)) {
                case '{' -> anidar(this::parseObject);
                case '[' -> anidar(this::parseArray);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private <T> T anidar(Supplier<T> dentro) {
            if (++profundidad > MAX_PROFUNDIDAD) {
                throw error("JSON anidado más de " + MAX_PROFUNDIDAD + " niveles");
            }
            try {
                return dentro.get();
            } finally {
                profundidad--;
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            skipWhitespace();
            if (peek() == '}') {
                at++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("se esperaba una clave");
                }
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("se esperaba ':'");
                }
                at++;
                map.put(key, parseValue());
                skipWhitespace();
                char next = peek();
                at++;
                if (next == '}') {
                    return map;
                }
                if (next != ',') {
                    throw error("se esperaba ',' o '}'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> items = new ArrayList<>();
            at++;
            skipWhitespace();
            if (peek() == ']') {
                at++;
                return items;
            }
            while (true) {
                items.add(parseValue());
                skipWhitespace();
                char next = peek();
                at++;
                if (next == ']') {
                    return items;
                }
                if (next != ',') {
                    throw error("se esperaba ',' o ']'");
                }
            }
        }

        private String parseString() {
            at++;
            var value = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw error("cadena sin cerrar");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return value.toString();
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw error("escape desconocido: \\" + escaped);
                }
            }
        }

        private Object parseNumber() {
            int start = at;
            if (peek() == '-' || peek() == '+') {
                at++;
            }
            boolean decimal = false;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c >= '0' && c <= '9') {
                    at++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    decimal = decimal || c == '.' || c == 'e' || c == 'E';
                    at++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, at);
            if (raw.isEmpty()) {
                throw error("número inválido");
            }
            try {
                if (decimal) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException cause) {
                throw error("número inválido: " + raw);
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, at)) {
                throw error("literal inválido");
            }
            at += literal.length();
            return value;
        }

        private char peek() {
            if (at >= text.length()) {
                throw error("fin inesperado");
            }
            return text.charAt(at);
        }

        private void skipWhitespace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON inválido en " + at + ": " + message);
        }
    }

    static final class Convert {

        private static final Map<Class<?>, Function<Object, Object>> CONVERSIONES = Map.ofEntries(
                Map.entry(String.class, String::valueOf),
                Map.entry(int.class, s -> number(s).intValue()),
                Map.entry(Integer.class, s -> number(s).intValue()),
                Map.entry(long.class, s -> number(s).longValue()),
                Map.entry(Long.class, s -> number(s).longValue()),
                Map.entry(double.class, s -> number(s).doubleValue()),
                Map.entry(Double.class, s -> number(s).doubleValue()),
                Map.entry(float.class, s -> number(s).floatValue()),
                Map.entry(Float.class, s -> number(s).floatValue()),
                Map.entry(short.class, s -> number(s).shortValue()),
                Map.entry(Short.class, s -> number(s).shortValue()),
                Map.entry(byte.class, s -> number(s).byteValue()),
                Map.entry(Byte.class, s -> number(s).byteValue()),
                Map.entry(boolean.class, Convert::booleano),
                Map.entry(Boolean.class, Convert::booleano),
                Map.entry(BigDecimal.class, s -> new BigDecimal(String.valueOf(s))),
                Map.entry(BigInteger.class, s -> new BigInteger(String.valueOf(s))),
                Map.entry(UUID.class, s -> UUID.fromString(String.valueOf(s))),
                Map.entry(Instant.class, s -> Instant.parse(String.valueOf(s))),
                Map.entry(LocalDate.class, s -> LocalDate.parse(String.valueOf(s))),
                Map.entry(LocalDateTime.class, s -> LocalDateTime.parse(String.valueOf(s))));

        private Convert() {
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static Object to(Object source, Class<?> type, Type generic) {
            if (type == Object.class) {
                return source;
            }
            if (source == null) {
                return type.isPrimitive() ? primitiveDefault(type) : null;
            }
            if (List.class.isAssignableFrom(type) && source instanceof List<?> items) {
                Class<?> element = argumentOf(generic, 0);
                if (element == null) {
                    return items;
                }
                List<Object> converted = new ArrayList<>(items.size());
                for (Object item : items) {
                    converted.add(to(item, element, null));
                }
                return converted;
            }
            if (type.isInstance(source) && !(source instanceof Number && type != source.getClass())) {
                return source;
            }
            var conversion = CONVERSIONES.get(type);
            if (conversion != null) {
                return conversion.apply(source);
            }
            if (type.isEnum()) {
                return Enum.valueOf((Class<Enum>) type, String.valueOf(source));
            }
            if (type.isArray() && source instanceof List<?> items) {
                Object array = Array.newInstance(type.getComponentType(), items.size());
                for (int i = 0; i < items.size(); i++) {
                    Array.set(array, i, to(items.get(i), type.getComponentType(), null));
                }
                return array;
            }
            if (Map.class.isAssignableFrom(type) && source instanceof Map<?, ?> map) {
                return new LinkedHashMap<>(map);
            }
            if (source instanceof Map<?, ?> map) {
                return type.isRecord() ? toRecord(map, type) : toBean(map, type);
            }
            throw new IllegalArgumentException("no se puede convertir " + source.getClass().getSimpleName()
                    + " a " + type.getSimpleName());
        }

        private static Object toRecord(Map<?, ?> map, Class<?> type) {
            RecordComponent[] components = type.getRecordComponents();
            Object[] arguments = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                arguments[i] = to(map.get(components[i].getName()), types[i], components[i].getGenericType());
            }
            try {
                Constructor<?> constructor = type.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            } catch (ReflectiveOperationException cause) {
                throw new IllegalArgumentException("no se pudo construir " + type.getSimpleName(), cause);
            }
        }

        private static Object toBean(Map<?, ?> map, Class<?> type) {
            try {
                Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object instance = constructor.newInstance();
                for (Field field : type.getDeclaredFields()) {
                    if (!map.containsKey(field.getName())) {
                        continue;
                    }
                    field.setAccessible(true);
                    field.set(instance, to(map.get(field.getName()), field.getType(), field.getGenericType()));
                }
                return instance;
            } catch (ReflectiveOperationException cause) {
                throw new IllegalArgumentException("no se pudo construir " + type.getSimpleName(), cause);
            }
        }

        private static Class<?> argumentOf(Type generic, int index) {
            if (generic instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments().length > index
                    && parameterized.getActualTypeArguments()[index] instanceof Class<?> argument) {
                return argument;
            }
            return null;
        }

        private static Object booleano(Object source) {
            return source instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(source));
        }

        private static Number number(Object source) {
            if (source instanceof Number value) {
                return value;
            }
            String text = String.valueOf(source).trim();
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        }

        private static Object primitiveDefault(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            return to(0, type, null);
        }
    }
}
