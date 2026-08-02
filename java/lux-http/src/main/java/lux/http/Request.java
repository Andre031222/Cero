package lux.http;

import java.io.InputStream;
import java.util.List;

public interface Request {

    HttpMethod method();

    String path();

    String rawQuery();

    String query(String name);

    List<String> queryAll(String name);

    Headers headers();

    String header(String name);

    String protocol();

    String remoteAddress();

    InputStream body();

    byte[] bodyBytes();

    String bodyText();
}
