package cero.data;

import cero.core.Json;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Una fila: un mapa de columna a valor, de solo lectura y con búsqueda insensible a mayúsculas.
 *
 * <p>Es un {@link Map} para que al anidarse en una respuesta JSON se serialice como objeto en vez
 * de introspeccionarse por getters. Las operaciones de escritura del contrato lanzan
 * {@link UnsupportedOperationException}: una fila se construye con {@link #of} o {@link #from}.
 */
public final class Row extends AbstractMap<String, Object> {

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, String> byLowercase = new HashMap<>();

    public Row() {
    }

    public static Row of(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("se esperaban pares clave/valor");
        }
        Row row = new Row();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.set(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return row;
    }

    public static Row from(Map<String, ?> source) {
        Row row = new Row();
        source.forEach(row::set);
        return row;
    }

    Row set(String column, Object value) {
        values.put(column, value);
        byLowercase.put(column.toLowerCase(), column);
        return this;
    }

    @Override
    public Object get(Object column) {
        String name = String.valueOf(column);
        if (values.containsKey(name)) {
            return values.get(name);
        }
        String actual = byLowercase.get(name.toLowerCase());
        return actual == null ? null : values.get(actual);
    }

    @Override
    public boolean containsKey(Object column) {
        return has(String.valueOf(column));
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return Collections.unmodifiableMap(values).entrySet();
    }

    public boolean has(String column) {
        return values.containsKey(column) || byLowercase.containsKey(column.toLowerCase());
    }

    public Set<String> columns() {
        return keySet();
    }

    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(values);
    }

    public String text(String column) {
        Object value = get(column);
        return value == null ? null : String.valueOf(value);
    }

    public String text(String column, String fallback) {
        String value = text(column);
        return value == null ? fallback : value;
    }

    public int integer(String column) {
        return number(column).intValue();
    }

    public long asLong(String column) {
        return number(column).longValue();
    }

    public double decimal(String column) {
        return number(column).doubleValue();
    }

    public BigDecimal exact(String column) {
        Object value = get(column);
        return switch (value) {
            case null -> null;
            case BigDecimal exact -> exact;
            case Number any -> new BigDecimal(any.toString());
            default -> new BigDecimal(String.valueOf(value));
        };
    }

    public boolean flag(String column) {
        Object value = get(column);
        return switch (value) {
            case null -> false;
            case Boolean bool -> bool;
            case Number number -> number.doubleValue() != 0;
            default -> Boolean.parseBoolean(String.valueOf(value));
        };
    }

    public LocalDate date(String column) {
        Object value = get(column);
        return switch (value) {
            case null -> null;
            case LocalDate date -> date;
            case LocalDateTime moment -> moment.toLocalDate();
            case java.sql.Date date -> date.toLocalDate();
            case java.sql.Timestamp stamp -> stamp.toLocalDateTime().toLocalDate();
            default -> LocalDate.parse(String.valueOf(value));
        };
    }

    public LocalDateTime moment(String column) {
        Object value = get(column);
        return switch (value) {
            case null -> null;
            case LocalDateTime moment -> moment;
            case LocalDate date -> date.atStartOfDay();
            case java.sql.Timestamp stamp -> stamp.toLocalDateTime();
            case Instant instant -> LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
            default -> LocalDateTime.parse(String.valueOf(value));
        };
    }

    public <T> T as(Class<T> type) {
        Map<String, Object> porNombre = Mapping.of(type).rename(this);
        return Json.bind(porNombre.isEmpty() ? toMap() : porNombre, type);
    }

    public String toJson() {
        return Json.write(values);
    }

    private Number number(String column) {
        Object value = get(column);
        return switch (value) {
            case null -> 0;
            case Number number -> number;
            case Boolean flag -> flag ? 1 : 0;
            default -> {
                String text = String.valueOf(value).trim();
                yield text.contains(".") ? Double.valueOf(text) : Long.valueOf(text);
            }
        };
    }
}
