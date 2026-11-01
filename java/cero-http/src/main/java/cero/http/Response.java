package cero.http;

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

    void redirect(String location);

    void redirectExternal(String location);

    OutputStream stream();

    Duplex switchProtocols();

    boolean committed();
}
