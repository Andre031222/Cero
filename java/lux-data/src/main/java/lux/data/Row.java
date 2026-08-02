package lux.data;

import lux.core.Json;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Row {

    private final Map<String, Object> values = new LinkedHashMap<>();

    public Row() {
    }

    public static Row of(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("se esperaban pares clave/valor");
        }
        Row row = new Row();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return row;
    }

    public static Row from(Map<String, ?> source) {
        Row row = new Row();
        source.forEach(row::put);
        return row;
    }

    public Row put(String column, Object value) {
        values.put(column, value);
        return this;
    }

    public Object get(String column) {
        return values.get(column);
    }

    public boolean has(String column) {
        return values.containsKey(column);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    public Set<String> columns() {
        return values.keySet();
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
        return Json.bind(toMap(), type);
    }

    public String toJson() {
        return Json.write(values);
    }

    @Override
    public String toString() {
        return values.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Row row && values.equals(row.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
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
