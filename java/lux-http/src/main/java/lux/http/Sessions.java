package lux.http;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class Sessions {

    static final String COOKIE = "LUXSESSION";

    private static final int SWEEP_EVERY = 256;

    private final SessionStore store;
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger sinceSweep = new AtomicInteger();
    private final long timeoutMillis;
    private final long vidaMaximaMillis;

    Sessions(long timeoutMillis) {
        this(timeoutMillis, SessionStore.inMemory(), 0);
    }

    Sessions(long timeoutMillis, SessionStore store) {
        this(timeoutMillis, store, 0);
    }

    Sessions(long timeoutMillis, SessionStore store, long vidaMaximaMillis) {
        this.timeoutMillis = timeoutMillis;
        this.store = store == null ? SessionStore.inMemory() : store;
        this.vidaMaximaMillis = vidaMaximaMillis;
    }

    Entry find(String id) {
        if (id == null) {
            return null;
        }
        SessionStore.Datos datos = store.load(id);
        if (datos == null) {
            return null;
        }
        long ahora = System.currentTimeMillis();
        // Dos topes: inactividad y vida total. Sin el segundo, una sesión que se toque de vez en
        // cuando vive para siempre — y una robada dura lo mismo que la use quien la robó.
        if (ahora - datos.ultimoAcceso() > timeoutMillis
                || (vidaMaximaMillis > 0 && ahora - datos.creada() > vidaMaximaMillis)) {
            store.remove(id);
            return null;
        }
        Entry entry = new Entry(this, id, datos);
        entry.tocar();
        return entry;
    }

    Entry create() {
        if (sinceSweep.incrementAndGet() >= SWEEP_EVERY) {
            sinceSweep.set(0);
            sweep();
        }
        long ahora = System.currentTimeMillis();
        Entry entry = new Entry(this, newId(), new SessionStore.Datos(new HashMap<>(), ahora, ahora));
        entry.guardar();
        return entry;
    }

    int size() {
        return store.size();
    }

    void sweep() {
        store.sweep(timeoutMillis);
    }

    private String newId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static final class Entry implements Session {

        private final Sessions owner;
        private volatile String id;          // deja de ser final: regenerateId() lo cambia
        private final Map<String, Object> values;
        private final long createdAt;

        private volatile long lastAccessAt;
        private volatile boolean valid = true;
        private volatile boolean created;
        private volatile boolean idRotado;

        Entry(Sessions owner, String id, SessionStore.Datos datos) {
            this.owner = owner;
            this.id = id;
            this.values = Collections.synchronizedMap(new HashMap<>(datos.valores()));
            this.createdAt = datos.creada();
            this.lastAccessAt = datos.ultimoAcceso();
        }

        void markCreated() {
            created = true;
        }

        /** {@code true} si hay que volver a emitir la cookie porque el identificador cambió. */
        boolean idRotado() {
            return idRotado;
        }

        void cookieEmitida() {
            idRotado = false;
        }

        void tocar() {
            lastAccessAt = System.currentTimeMillis();
            guardar();
        }

        void guardar() {
            synchronized (values) {
                owner.store.save(id,
                        new SessionStore.Datos(new HashMap<>(values), createdAt, lastAccessAt));
            }
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Object get(String key) {
            requireValid();
            return values.get(key);
        }

        @Override
        public <T> T get(String key, Class<T> type) {
            Object found = get(key);
            return type.isInstance(found) ? type.cast(found) : null;
        }

        @Override
        public void set(String key, Object value) {
            requireValid();
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
            guardar();
        }

        @Override
        public void remove(String key) {
            requireValid();
            values.remove(key);
            guardar();
        }

        @Override
        public Set<String> keys() {
            requireValid();
            synchronized (values) {
                return Set.copyOf(values.keySet());
            }
        }

        @Override
        public void regenerateId() {
            if (!valid) {
                throw new IllegalStateException("la sesión ya no vale: no se puede rotar su id");
            }
            String anterior = id;
            String nuevo = owner.newId();
            synchronized (values) {
                id = nuevo;
                owner.store.save(nuevo,
                        new SessionStore.Datos(new HashMap<>(values), createdAt, lastAccessAt));
            }
            // Se borra el viejo DESPUÉS de guardar el nuevo: si algo falla en medio, se queda la
            // sesión duplicada un rato, que es mucho mejor que perderla.
            owner.store.remove(anterior);
            idRotado = true;
        }

        @Override
        public void invalidate() {
            valid = false;
            values.clear();
            owner.store.remove(id);
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public boolean created() {
            return created;
        }

        @Override
        public long createdAt() {
            return createdAt;
        }

        @Override
        public long lastAccessAt() {
            return lastAccessAt;
        }

        private void requireValid() {
            if (!valid) {
                throw new IllegalStateException("sesión invalidada");
            }
        }
    }
}
