package cero.core;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class Cache {

    public interface Backend {

        void put(String cache, String clave, Object valor, long segundos);

        Object fetch(String cache, String clave);

        boolean has(String cache, String clave);

        void evict(String cache, String clave);

        void clear(String cache);
    }

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

    public Cache maxEntries(int tope) {
        maxEntradas = Math.max(0, tope);
        return this;
    }

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

    public Cache put(String clave, Object valor, Duration caducidad) {
        long milis = caducidad == null || caducidad.isNegative() ? 0 : caducidad.toMillis();
        if (backend != null) {
            backend.put(nombre, clave, valor, milis == 0 ? 0 : Math.max(1, milis / 1_000));
            return this;
        }
        long vence = milis > 0 ? System.currentTimeMillis() + milis : 0;
        hacerSitio(clave);
        almacen.put(clave, new Entrada(valor, vence));
        return this;
    }

    public <T> T fetch(String clave, Class<T> tipo) {
        Object valor = fetch(clave);
        return tipo.isInstance(valor) ? tipo.cast(valor) : null;
    }

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

    public int size() {
        return almacen.size();
    }

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
