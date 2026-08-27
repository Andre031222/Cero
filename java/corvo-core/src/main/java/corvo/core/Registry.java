package corvo.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class Registry {

    private final Map<Class<?>, Object> instances = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> cerrojos = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<Class<?>>> building = ThreadLocal.withInitial(ArrayDeque::new);

    public <T> Registry add(T instance) {
        return add(instance.getClass(), instance);
    }

    @SuppressWarnings("unchecked")
    public <T> Registry add(Class<?> type, T instance) {
        instances.put(type, instance);
        for (Class<?> contract : type.getInterfaces()) {
            instances.putIfAbsent(contract, instance);
        }
        return this;
    }

    public boolean has(Class<?> type) {
        return instances.containsKey(type);
    }

    public Collection<Object> all() {
        return instances.values();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        Object found = instances.get(type);
        if (found != null) {
            return (T) found;
        }
        if (!type.isAnnotationPresent(Service.class)) {
            throw new IllegalStateException("no hay un servicio registrado para " + type.getName()
                    + "; regístralo con add() o anótalo con @Service");
        }

        // La construcción va FUERA del mapa, a propósito. build() vuelve a entrar en get() por
        // cada dependencia del constructor, así que resolver dentro de computeIfAbsent anida
        // llamadas sobre el mismo ConcurrentHashMap: en cuanto dos eslabones de la cadena caen
        // en el mismo bin, lanza IllegalStateException("Recursive update"). No era un límite de
        // profundidad, era una colisión de bins que con cadenas largas deja de ser casualidad.
        //
        // El cerrojo por tipo conserva lo que daba computeIfAbsent —una sola instancia aunque
        // dos hilos pidan el mismo servicio a la vez— sin meter la recursión dentro del mapa.
        Object cerrojo = cerrojos.computeIfAbsent(type, clave -> new Object());
        synchronized (cerrojo) {
            Object yaEstaba = instances.get(type);
            if (yaEstaba != null) {
                return (T) yaEstaba;
            }
            Object recien = build(type);
            instances.put(type, recien);
            return (T) recien;
        }
    }

    public <T> T create(Class<T> type) {
        return type.cast(build(type));
    }

    public void inject(Object target) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Inject.class)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(target, get(field.getType()));
                } catch (IllegalAccessException cause) {
                    throw new IllegalStateException("no se pudo inyectar " + field.getName(), cause);
                }
            }
        }
    }

    private Object build(Class<?> type) {
        Deque<Class<?>> stack = building.get();
        if (stack.contains(type)) {
            throw new IllegalStateException("dependencia circular: "
                    + stack.stream().map(Class::getSimpleName).collect(Collectors.joining(" → "))
                    + " → " + type.getSimpleName());
        }
        stack.push(type);
        try {
            Constructor<?> constructor = pickConstructor(type);
            Object[] arguments = new Object[constructor.getParameterCount()];
            Class<?>[] parameters = constructor.getParameterTypes();
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = get(parameters[i]);
            }
            constructor.setAccessible(true);
            Object instance = constructor.newInstance(arguments);
            inject(instance);
            return instance;
        } catch (ReflectiveOperationException cause) {
            throw new IllegalStateException("no se pudo construir " + type.getName(), cause);
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                building.remove();
            }
        }
    }

    private static Constructor<?> pickConstructor(Class<?> type) {
        Constructor<?>[] declared = type.getDeclaredConstructors();
        for (Constructor<?> candidate : declared) {
            if (candidate.isAnnotationPresent(Inject.class)) {
                return candidate;
            }
        }
        for (Constructor<?> candidate : declared) {
            if (candidate.getParameterCount() == 0) {
                return candidate;
            }
        }
        if (declared.length == 1) {
            return declared[0];
        }
        throw new IllegalStateException(type.getName()
                + " necesita un constructor sin argumentos o uno anotado con @Inject");
    }
}
