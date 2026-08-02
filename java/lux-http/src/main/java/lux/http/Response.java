package lux.http;

import java.io.OutputStream;

public interface Response {

    Response status(int code);

    int status();

    Headers headers();

    Response header(String name, String value);

    Response type(String contentType);

    void send(byte[] body);

    void send(String body);

    void text(String body);

    void html(String body);

    void json(String body);

    void redirect(String location);

    OutputStream stream();

    boolean committed();
}
