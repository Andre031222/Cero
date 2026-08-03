package lux.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bus de eventos dentro del proceso.
 *
 * <pre>
 *   record UsuarioCreado(long id, String email) {}
 *
 *   Events bus = Events.bus();
 *   bus.on(UsuarioCreado.class, evento -&gt; correo.bienvenida(evento.email()));
 *   bus.publish(new UsuarioCreado(id, email));
 * </pre>
 *
 * <p>Los oyentes corren en el hilo de quien publica, en el orden en que se registraron. Uno que
 * falle no tumba al publicador ni impide los demás. Para publicar sin bloquear:
 * {@code jobs.run(() -> bus.publish(evento))}.
 */
public final class Events {

    private static final Log log = Log.of(Events.class);

    private final Map<Class<?>, List<Oyente>> oyentes = new ConcurrentHashMap<>();

    private Events() {
    }

    public static Events bus() {
        return new Events();
    }

    /** Registra un oyente para un tipo de evento. */
    public <T> Events on(Class<T> tipo, Consumer<T> oyente) {
        oyentes.computeIfAbsent(tipo, clave -> new CopyOnWriteArrayList<>())
                .add(new Oyente(null, null, evento -> oyente.accept(tipo.cast(evento))));
        return this;
    }

    /** Registra los métodos {@link Listens} del objeto. Llamarlo dos veces no duplica oyentes. */
    public Events listeners(Object servicio) {
        for (Method metodo : servicio.getClass().getMethods()) {
            if (!metodo.isAnnotationPresent(Listens.class)) {
                continue;
            }
            if (metodo.getParameterCount() != 1) {
                throw new IllegalArgumentException("@Listens necesita exactamente un parámetro: "
                        + servicio.getClass().getSimpleName() + "." + metodo.getName());
            }
            Class<?> tipo = metodo.getParameterTypes()[0];
            List<Oyente> registrados = oyentes.computeIfAbsent(tipo, clave -> new CopyOnWriteArrayList<>());
            if (registrados.stream().anyMatch(candidato -> candidato.esMismo(servicio, metodo))) {
                continue;
            }
            metodo.setAccessible(true);
            registrados.add(new Oyente(servicio, metodo, evento -> invocar(servicio, metodo, evento)));
        }
        return this;
    }

    /** Registra los oyentes de todos los servicios de un registro. */
    public Events listenersFrom(Registry registro) {
        for (Object servicio : registro.all()) {
            listeners(servicio);
        }
        return this;
    }

    /** Entrega el evento a quien escuche su tipo o cualquiera de sus supertipos. */
    public void publish(Object evento) {
        if (evento == null) {
            throw new IllegalArgumentException("no se publica un evento nulo");
        }
        Class<?> tipo = evento.getClass();
        for (Map.Entry<Class<?>, List<Oyente>> entrada : oyentes.entrySet()) {
            if (!entrada.getKey().isAssignableFrom(tipo)) {
                continue;
            }
            for (Oyente oyente : entrada.getValue()) {
                try {
                    oyente.entrega.accept(evento);
                } catch (RuntimeException fallo) {
                    log.warn("un oyente de {} falló: {}", tipo.getSimpleName(), fallo);
                }
            }
        }
    }

    /** Quita todos los oyentes de un tipo. */
    public Events off(Class<?> tipo) {
        oyentes.remove(tipo);
        return this;
    }

    /** Cuántos oyentes hay registrados para un tipo exacto. */
    public int listenerCount(Class<?> tipo) {
        List<Oyente> registrados = oyentes.get(tipo);
        return registrados == null ? 0 : registrados.size();
    }

    /** Los tipos de evento con al menos un oyente. */
    public List<Class<?>> types() {
        return new ArrayList<>(oyentes.keySet());
    }

    private static void invocar(Object servicio, Method metodo, Object evento) {
        try {
            metodo.invoke(servicio, evento);
        } catch (InvocationTargetException envuelto) {
            Throwable causa = envuelto.getCause();
            throw causa instanceof RuntimeException fallo
                    ? fallo
                    : new IllegalStateException(causa);
        } catch (IllegalAccessException imposible) {
            throw new IllegalStateException(imposible);
        }
    }

    private record Oyente(Object servicio, Method metodo, Consumer<Object> entrega) {

        boolean esMismo(Object otroServicio, Method otroMetodo) {
            return servicio == otroServicio && otroMetodo.equals(metodo);
        }
    }
}
