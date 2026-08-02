package lux.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.Map;

final class IncomingRequest implements Request {

    private final HttpMethod method;
    private final String path;
    private final String rawQuery;
    private final String protocol;
    private final Headers headers;
    private final InputStream body;
    private final String remoteAddress;

    private Map<String, List<String>> params;
    private byte[] cachedBody;

    IncomingRequest(HttpMethod method, String path, String rawQuery, String protocol,
                    Headers headers, InputStream body, String remoteAddress) {
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery;
        this.protocol = protocol;
        this.headers = headers;
        this.body = body;
        this.remoteAddress = remoteAddress;
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String rawQuery() {
        return rawQuery;
    }

    @Override
    public String query(String name) {
        List<String> found = params().get(name);
        return found == null || found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<String> queryAll(String name) {
        return params().getOrDefault(name, List.of());
    }

    @Override
    public Headers headers() {
        return headers;
    }

    @Override
    public String header(String name) {
        return headers.get(name);
    }

    @Override
    public String protocol() {
        return protocol;
    }

    @Override
    public String remoteAddress() {
        return remoteAddress;
    }

    @Override
    public InputStream body() {
        return body;
    }

    @Override
    public byte[] bodyBytes() {
        if (cachedBody == null) {
            try {
                cachedBody = readAll();
            } catch (IOException cause) {
                throw new HttpException(400, "no se pudo leer el cuerpo", cause);
            }
        }
        return cachedBody;
    }

    @Override
    public String bodyText() {
        return new String(bodyBytes(), charset());
    }

    boolean bodyDrained() {
        if (body instanceof FixedBody fixed) {
            return fixed.drained();
        }
        if (body instanceof ChunkedBody chunked) {
            return chunked.drained();
        }
        return true;
    }

    void drainBody() throws IOException {
        byte[] scratch = new byte[8_192];
        while (body.read(scratch, 0, scratch.length) >= 0) {
            continue;
        }
    }

    private Map<String, List<String>> params() {
        if (params == null) {
            params = Url.parseQuery(rawQuery);
        }
        return params;
    }

    private Charset charset() {
        String type = headers.get("Content-Type");
        if (type == null) {
            return StandardCharsets.UTF_8;
        }
        int mark = type.toLowerCase().indexOf("charset=");
        if (mark < 0) {
            return StandardCharsets.UTF_8;
        }
        String name = type.substring(mark + 8).trim();
        int semicolon = name.indexOf(';');
        if (semicolon >= 0) {
            name = name.substring(0, semicolon).trim();
        }
        name = name.replace("\"", "");
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException cause) {
            return StandardCharsets.UTF_8;
        }
    }

    private byte[] readAll() throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream(Math.max(64, body.available()));
        byte[] scratch = new byte[8_192];
        int read;
        while ((read = body.read(scratch, 0, scratch.length)) >= 0) {
            collected.write(scratch, 0, read);
        }
        return collected.toByteArray();
    }
}
