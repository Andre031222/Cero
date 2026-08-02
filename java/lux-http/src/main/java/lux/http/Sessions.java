package lux.http;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class Sessions {

    static final String COOKIE = "LUXSESSION";

    private static final int SWEEP_EVERY = 256;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger sinceSweep = new AtomicInteger();
    private final long timeoutMillis;

    Sessions(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    Entry find(String id) {
        if (id == null) {
            return null;
        }
        Entry entry = entries.get(id);
        if (entry == null) {
            return null;
        }
        if (expired(entry)) {
            entries.remove(id, entry);
            return null;
        }
        entry.lastAccessAt = System.currentTimeMillis();
        return entry;
    }

    Entry create() {
        if (sinceSweep.incrementAndGet() >= SWEEP_EVERY) {
            sinceSweep.set(0);
            sweep();
        }
        Entry entry = new Entry(this, newId());
        entries.put(entry.id, entry);
        return entry;
    }

    int size() {
        return entries.size();
    }

    void sweep() {
        entries.values().removeIf(this::expired);
    }

    private boolean expired(Entry entry) {
        return !entry.valid || System.currentTimeMillis() - entry.lastAccessAt > timeoutMillis;
    }

    private String newId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static final class Entry implements Session {

        private final Sessions owner;
        private final String id;
        private final Map<String, Object> values = Collections.synchronizedMap(new HashMap<>());
        private final long createdAt = System.currentTimeMillis();

        private volatile long lastAccessAt = createdAt;
        private volatile boolean valid = true;
        private volatile boolean created;

        Entry(Sessions owner, String id) {
            this.owner = owner;
            this.id = id;
        }

        void markCreated() {
            created = true;
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
        }

        @Override
        public void remove(String key) {
            requireValid();
            values.remove(key);
        }

        @Override
        public Set<String> keys() {
            requireValid();
            synchronized (values) {
                return Set.copyOf(values.keySet());
            }
        }

        @Override
        public void invalidate() {
            valid = false;
            values.clear();
            owner.entries.remove(id, this);
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
