package lux.http;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

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

    boolean secure();

    String cookie(String name);

    Map<String, String> cookies();

    Session session();

    Session session(boolean create);

    List<Part> parts();

    Part part(String name);

    String field(String name);

    InputStream body();

    byte[] bodyBytes();

    String bodyText();
}
