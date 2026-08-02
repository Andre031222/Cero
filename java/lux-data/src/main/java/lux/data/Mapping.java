package lux.data;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class Mapping {

    private static final Map<Class<?>, Mapping> CACHE = new ConcurrentHashMap<>();

    private final Class<?> type;
    private final String table;
    private final List<Property> properties;
    private final Property identity;

    private Mapping(Class<?> type, String table, List<Property> properties, Property identity) {
        this.type = type;
        this.table = table;
        this.properties = properties;
        this.identity = identity;
    }

    static Mapping of(Class<?> type) {
        return CACHE.computeIfAbsent(type, Mapping::build);
    }

    String table() {
        return table;
    }

    String idColumn() {
        return require(identity).column();
    }

    boolean idGenerated() {
        return require(identity).generated();
    }

    Object idOf(Object entity) {
        return require(identity).read(entity);
    }

    Row toRow(Object entity, boolean includeId) {
        Row row = new Row();
        for (Property property : properties) {
            if (!includeId && property.identity()) {
                continue;
            }
            row.put(property.column(), property.read(entity));
        }
        return row;
    }

    Map<String, Object> rename(Row row) {
        Map<String, Object> byProperty = new LinkedHashMap<>();
        for (Property property : properties) {
            if (row.has(property.column())) {
                byProperty.put(property.name(), row.get(property.column()));
            } else if (row.has(property.name())) {
                byProperty.put(property.name(), row.get(property.name()));
            }
        }
        return byProperty;
    }

    private Property require(Property property) {
        if (property == null) {
            throw new DataException(type.getSimpleName() + " no declara ninguna propiedad con @Id");
        }
        return property;
    }

    private static Mapping build(Class<?> type) {
        Table annotation = type.getAnnotation(Table.class);
        String table = annotation != null ? annotation.value() : defaultTable(type);

        List<Property> properties = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                properties.add(Property.of(component.getName(), component, entity -> read(component, entity)));
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                properties.add(Property.of(field.getName(), field, entity -> read(field, entity)));
            }
        }

        Property identity = properties.stream().filter(Property::identity).findFirst().orElse(null);
        return new Mapping(type, table, List.copyOf(properties), identity);
    }

    private static String defaultTable(Class<?> type) {
        String name = type.getSimpleName();
        StringBuilder snake = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                snake.append('_');
            }
            snake.append(Character.toLowerCase(c));
        }
        return snake.append('s').toString();
    }

    private static Object read(RecordComponent component, Object entity) {
        try {
            component.getAccessor().setAccessible(true);
            return component.getAccessor().invoke(entity);
        } catch (ReflectiveOperationException cause) {
            throw new DataException("no se pudo leer " + component.getName(), cause);
        }
    }

    private static Object read(Field field, Object entity) {
        try {
            return field.get(entity);
        } catch (IllegalAccessException cause) {
            throw new DataException("no se pudo leer " + field.getName(), cause);
        }
    }

    private record Property(String name, String column, boolean identity, boolean generated,
                            java.util.function.Function<Object, Object> reader) {

        static Property of(String name, AnnotatedElement element,
                           java.util.function.Function<Object, Object> reader) {
            Column column = element.getAnnotation(Column.class);
            Id id = element.getAnnotation(Id.class);
            return new Property(name,
                    column != null ? column.value() : name,
                    id != null,
                    id == null || id.generated(),
                    reader);
        }

        Object read(Object entity) {
            return reader.apply(entity);
        }
    }
}
