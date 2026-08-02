package lux.http;

import java.util.Set;

public interface Session {

    String id();

    Object get(String key);

    <T> T get(String key, Class<T> type);

    void set(String key, Object value);

    void remove(String key);

    Set<String> keys();

    void invalidate();

    boolean valid();

    boolean created();

    long createdAt();

    long lastAccessAt();
}
