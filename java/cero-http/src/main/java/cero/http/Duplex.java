package cero.http;

import java.io.InputStream;
import java.io.OutputStream;

public interface Duplex {

    InputStream in();

    OutputStream out();

    Request request();
}
