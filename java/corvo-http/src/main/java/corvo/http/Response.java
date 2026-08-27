package corvo.http;

import java.io.OutputStream;

public interface Response {

    Response status(int code);

    int status();

    Headers headers();

    Response header(String name, String value);

    Response type(String contentType);

    Response cookie(Cookie cookie);

    void send(byte[] body);

    void send(String body);

    void text(String body);

    void html(String body);

    void json(String body);

    /** Redirige dentro del sitio. Un destino externo se rechaza: sería una redirección abierta. */
    void redirect(String location);

    /** Redirige fuera del sitio, a propósito. Para proveedores OAuth y poco más. */
    void redirectExternal(String location);

    OutputStream stream();

    /**
     * Cede la conexión a otro protocolo: escribe {@code 101 Switching Protocols} con las
     * cabeceras ya puestas y devuelve los flujos crudos.
     */
    Duplex switchProtocols();

    boolean committed();
}
