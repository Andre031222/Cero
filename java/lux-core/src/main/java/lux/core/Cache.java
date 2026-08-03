package lux.core;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caché en memoria con caducidad, sin dependencias y sin hilos.
 *
 * <pre>
 *   Cache catalogo = Cache.named("catalogo").maxEntries(5_000);
 *
 *   catalogo.put("todos", articulos, Duration.ofMinutes(5));
 *   List&lt;Articulo&gt; articulos = catalogo.fetch("todos", List.class);
 *
 *   // o de una vez, que es como se usa de verdad
 *   var articulos = catalogo.computeIfAbsent("todos", Duration.ofMinutes(5), repo::todos);
 * </pre>
 *
 * <p>Sin registro global y sin hilo de limpieza: una caché es un objeto que se crea y se inyecta,
 * lo caducado se descarta al leerlo y se barre al llenarse. Para un barrido periódico,
 * {@link #sweep()} se engancha a un {@code Cron} en una línea.
 */
public final class Cache {

    /** Almacén externo. Declarando uno, la caché deja de guardar en memoria. */
    public interface Backend {

        void put(String cache, String clave, Object valor, long segundos);

        Object fetch(String cache, String clave);

        boolean has(String cache, String clave);

        void evict(String cache, String clave);

        void clear(String cache);
    }

    /** Cuántas entradas se miran para elegir víctima al desalojar. Muestreo estilo Redis. */
    private static final int MUESTRA = 64;

    private final String nombre;
    private final Map<String, Entrada> almacen = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> cerrojos = new ConcurrentHashMap<>();

    private int maxEntradas = 10_000;
    private Backend backend;

    private Cache(String nombre) {
        this.nombre = nombre;
    }

    public static Cache named(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("la caché necesita un nombre");
        }
        return new Cache(nombre);
    }

    /** Tope de entradas; al llenarse desaloja la más fría de una muestra. 0 quita el tope. */
    public Cache maxEntries(int tope) {
        maxEntradas = Math.max(0, tope);
        return this;
    }

    /** Delega en un almacén externo. A partir de aquí no se guarda nada en memoria. */
    public Cache backedBy(Backend almacenExterno) {
        backend = almacenExterno;
        return this;
    }

    public String name() {
        return nombre;
    }

    public Cache put(String clave, Object valor) {
        return put(clave, valor, Duration.ZERO);
    }

    /** Guarda con caducidad. {@link Duration#ZERO} o negativa significa «sin caducidad». */
    public Cache put(String clave, Object valor, Duration caducidad) {
        long milis = caducidad == null || caducidad.isNegative() ? 0 : caducidad.toMillis();
        if (backend != null) {
            // Los almacenes externos hablan en segundos; menos de uno se sube a uno.
            backend.put(nombre, clave, valor, milis == 0 ? 0 : Math.max(1, milis / 1_000));
            return this;
        }
        long vence = milis > 0 ? System.currentTimeMillis() + milis : 0;
        hacerSitio(clave);
        almacen.put(clave, new Entrada(valor, vence));
        return this;
    }

    /** El valor si sigue vivo y es del tipo pedido; si no, {@code null}. */
    public <T> T fetch(String clave, Class<T> tipo) {
        Object valor = fetch(clave);
        return tipo.isInstance(valor) ? tipo.cast(valor) : null;
    }

    /** El valor si sigue vivo, sin comprobar el tipo. */
    public Object fetch(String clave) {
        if (backend != null) {
            return backend.fetch(nombre, clave);
        }
        Entrada entrada = viva(clave);
        return entrada == null ? null : entrada.valor;
    }

    public boolean has(String clave) {
        return backend != null ? backend.has(nombre, clave) : viva(clave) != null;
    }

    /** Dos hilos que pidan la misma clave a la vez cargan una sola vez. */
    public <T> T computeIfAbsent(String clave, Duration caducidad, Callable<T> carga) {
        Object cacheado = fetch(clave);
        if (cacheado != null) {
            return cast(cacheado);
        }
        ReentrantLock cerrojo = cerrojos.computeIfAbsent(clave, k -> new ReentrantLock());
        cerrojo.lock();
        try {
            cacheado = fetch(clave);
            if (cacheado != null) {
                return cast(cacheado);
            }
            T valor = carga.call();
            if (valor != null) {
                put(clave, valor, caducidad);
            }
            return valor;
        } catch (RuntimeException fallo) {
            throw fallo;
        } catch (Exception fallo) {
            throw new IllegalStateException("falló la carga de " + nombre + "/" + clave, fallo);
        } finally {
            cerrojo.unlock();
        }
    }

    public Cache evict(String clave) {
        if (backend != null) {
            backend.evict(nombre, clave);
            return this;
        }
        almacen.remove(clave);
        cerrojos.remove(clave);
        return this;
    }

    public Cache clear() {
        if (backend != null) {
            backend.clear(nombre);
            return this;
        }
        almacen.clear();
        cerrojos.clear();
        return this;
    }

    /** Entradas guardadas, incluidas las caducadas que nadie ha tocado todavía. */
    public int size() {
        return almacen.size();
    }

    /** Descarta lo caducado. La caché es correcta sin esto; solo libera memoria antes. */
    public void sweep() {
        almacen.entrySet().removeIf(entrada -> entrada.getValue().caducada());
        cerrojos.entrySet().removeIf(entrada -> !almacen.containsKey(entrada.getKey())
                && !entrada.getValue().isLocked()
                && !entrada.getValue().hasQueuedThreads());
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object valor) {
        return (T) valor;
    }

    private Entrada viva(String clave) {
        Entrada entrada = almacen.get(clave);
        if (entrada == null) {
            return null;
        }
        if (entrada.caducada()) {
            almacen.remove(clave);
            return null;
        }
        entrada.ultimoAcceso = System.currentTimeMillis();
        return entrada;
    }

    private void hacerSitio(String clave) {
        if (maxEntradas <= 0 || almacen.size() < maxEntradas || almacen.containsKey(clave)) {
            return;
        }
        sweep();
        while (almacen.size() >= maxEntradas) {
            String masFria = masFria();
            if (masFria == null) {
                return;
            }
            almacen.remove(masFria);
            cerrojos.remove(masFria);
        }
    }

    /** La menos usada de una muestra, no de todo el mapa: recorrerlo entero sería lineal. */
    private String masFria() {
        String masFria = null;
        long masVieja = Long.MAX_VALUE;
        int vistas = 0;
        for (Map.Entry<String, Entrada> entrada : almacen.entrySet()) {
            long acceso = entrada.getValue().ultimoAcceso;
            if (acceso < masVieja) {
                masVieja = acceso;
                masFria = entrada.getKey();
            }
            if (++vistas >= MUESTRA) {
                break;
            }
        }
        return masFria;
    }

    private static final class Entrada {

        final Object valor;
        final long vence;
        volatile long ultimoAcceso = System.currentTimeMillis();

        Entrada(Object valor, long vence) {
            this.valor = valor;
            this.vence = vence;
        }

        boolean caducada() {
            return vence > 0 && System.currentTimeMillis() > vence;
        }
    }
}
