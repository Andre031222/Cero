package corvo.http;

import java.util.Set;

public interface Session {

    String id();

    Object get(String key);

    <T> T get(String key, Class<T> type);

    void set(String key, Object value);

    void remove(String key);

    Set<String> keys();

    /**
     * Cambia el identificador conservando el contenido. Hay que llamarlo <b>justo después de
     * identificarse</b>: si no, el identificador que el visitante tenía antes de entrar sigue
     * siendo válido después, y quien lo hubiera fijado de antemano —desde un subdominio, un
     * kiosco compartido, un XSS en otro sitio del mismo dominio— se queda con una sesión
     * autenticada. Es fijación de sesión.
     *
     * <p>A diferencia de {@link #invalidate()}, no se pierde nada: ni el token CSRF ni lo que
     * hubiera guardado el flujo de acceso.
     */
    void regenerateId();

    void invalidate();

    boolean valid();

    boolean created();

    long createdAt();

    long lastAccessAt();
}
