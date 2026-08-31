package corvo.view;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Scope {

    private final Scope parent;
    private final Object model;
    private final Map<String, Object> globals;
    private final Map<String, Object> locals = new HashMap<>(4);

    Scope(Object model) {
        this(null, model, Map.of());
    }

    Scope(Object model, Map<String, Object> globals) {
        this(null, model, globals);
    }

    private Scope(Scope parent, Object model, Map<String, Object> globals) {
        this.parent = parent;
        this.model = model;
        this.globals = globals;
    }

    Scope child() {
        return new Scope(this, model, globals);
    }

    void put(String name, Object value) {
        locals.put(name, value);
    }

    Object lookup(String name) {
        for (Scope scope = this; scope != null; scope = scope.parent) {
            if (scope.locals.containsKey(name)) {
                return scope.locals.get(name);
            }
        }
        Object delModelo = property(model, name);
        // Los globales van los ÚLTIMOS a propósito: si una plantilla recibe un modelo con un
        // campo que se llama igual que un global, gana el modelo. Al revés, añadir un global
        // nuevo podría tapar en silencio un dato de una plantilla que ya funcionaba.
        return delModelo != null ? delModelo : globals.get(name);
    }

    static Object property(Object target, String name) {
        if (target == null) {
            return null;
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(name);
        }
        if (target instanceof List<?> list) {
            int index = asIndex(name);
            if (index >= 0 && index < list.size()) {
                return list.get(index);
            }
            if (name.equals("size")) {
                return list.size();
            }
            return null;
        }
        if (target.getClass().isArray()) {
            int index = asIndex(name);
            Object[] items = (Object[]) target;
            if (index >= 0 && index < items.length) {
                return items[index];
            }
            return name.equals("size") ? items.length : null;
        }
        if (target instanceof String text) {
            return name.equals("size") ? text.length() : null;
        }
        return reflect(target, name);
    }

    private static Object reflect(Object target, String name) {
        Class<?> type = target.getClass();
        for (String candidate : accessorNames(name)) {
            try {
                Method method = type.getMethod(candidate);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException ignored) {
                continue;
            }
        }
        try {
            Field field = type.getField(name);
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static List<String> accessorNames(String name) {
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return List.of(name, "get" + capitalized, "is" + capitalized);
    }

    private static int asIndex(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') {
                return -1;
            }
        }
        return name.isEmpty() ? -1 : Integer.parseInt(name);
    }
}
