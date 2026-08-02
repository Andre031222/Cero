package lux.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class OutgoingResponse implements Response {

    private static final byte[] NO_BODY = new byte[0];

    private final OutputStream target;
    private final IncomingRequest request;
    private final ServerOptions options;
    private final Headers headers = new Headers();
    private final boolean headOnly;

    private int status = 200;
    private boolean committed;
    private boolean keepAlive = true;
    private ChunkedOutput chunked;

    OutgoingResponse(OutputStream target, IncomingRequest request, ServerOptions options) {
        this.target = target;
        this.request = request;
        this.options = options;
        this.headOnly = request != null && request.method() == HttpMethod.HEAD;
    }

    @Override
    public Response status(int code) {
        requireOpen();
        status = code;
        return this;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public Headers headers() {
        return headers;
    }

    @Override
    public Response header(String name, String value) {
        requireOpen();
        rejectControlCharacters(name, value);
        headers.set(name, value);
        return this;
    }

    @Override
    public Response type(String contentType) {
        return header("Content-Type", contentType);
    }

    @Override
    public Response cookie(Cookie cookie) {
        requireOpen();
        String encoded = cookie.encode();
        rejectControlCharacters("Set-Cookie", encoded);
        headers.add("Set-Cookie", encoded);
        return this;
    }

    @Override
    public void send(byte[] body) {
        commit(body);
    }

    @Override
    public void send(String body) {
        commit(body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void text(String body) {
        typeIfAbsent("text/plain; charset=utf-8");
        send(body);
    }

    @Override
    public void html(String body) {
        typeIfAbsent("text/html; charset=utf-8");
        send(body);
    }

    @Override
    public void json(String body) {
        typeIfAbsent("application/json");
        send(body);
    }

    @Override
    public void redirect(String location) {
        if (status == 200) {
            status = 302;
        }
        header("Location", location);
        commit(NO_BODY);
    }

    @Override
    public OutputStream stream() {
        requireOpen();
        committed = true;
        try {
            writeHead(-1, true, null);
            chunked = new ChunkedOutput(target);
            return chunked;
        } catch (IOException cause) {
            throw new UncheckedHttpException(cause);
        }
    }

    @Override
    public boolean committed() {
        return committed;
    }

    void keepAlive(boolean value) {
        keepAlive = value;
    }

    void reset() {
        requireOpen();
        headers.clear();
        status = 200;
    }

    void finish() throws IOException {
        if (chunked != null) {
            chunked.close();
            return;
        }
        if (!committed) {
            commit(NO_BODY);
        }
    }

    private void requireOpen() {
        if (committed) {
            throw new IllegalStateException("respuesta ya enviada");
        }
    }

    private void typeIfAbsent(String contentType) {
        if (!headers.has("Content-Type")) {
            headers.set("Content-Type", contentType);
        }
    }

    private void commit(byte[] body) {
        requireOpen();
        committed = true;

        boolean withBody = HttpStatus.allowsBody(status);
        byte[] payload = withBody ? body : NO_BODY;
        String encoding = null;

        if (withBody && Gzip.worthwhile(request, headers, options, payload.length)) {
            byte[] compressed = Gzip.compress(payload);
            if (compressed.length < payload.length) {
                payload = compressed;
                encoding = "gzip";
            }
        }

        try {
            writeHead(withBody ? payload.length : -1, false, encoding);
            if (withBody && !headOnly && payload.length > 0) {
                target.write(payload);
            }
            target.flush();
        } catch (IOException cause) {
            throw new UncheckedHttpException(cause);
        }
    }

    private void writeHead(long contentLength, boolean useChunked, String encoding) throws IOException {
        StringBuilder head = new StringBuilder(192);
        head.append("HTTP/1.1 ").append(status).append(' ').append(HttpStatus.reason(status)).append("\r\n");
        head.append("Date: ").append(HttpDate.now()).append("\r\n");

        if (useChunked) {
            head.append("Transfer-Encoding: chunked\r\n");
        } else if (contentLength >= 0) {
            head.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        if (encoding != null) {
            head.append("Content-Encoding: ").append(encoding).append("\r\n");
            head.append("Vary: Accept-Encoding\r\n");
        }
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");

        if (request != null) {
            Cookie pending = request.pendingSessionCookie();
            if (pending != null) {
                head.append("Set-Cookie: ").append(pending.encode()).append("\r\n");
            }
        }

        for (int i = 0; i < headers.size(); i++) {
            rejectControlCharacters(headers.name(i), headers.value(i));
            head.append(headers.name(i)).append(": ").append(headers.value(i)).append("\r\n");
        }
        head.append("\r\n");

        target.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void rejectControlCharacters(String name, String value) {
        if (hasControlCharacter(name) || hasControlCharacter(value)) {
            throw new HttpException(500, "cabecera con caracteres de control: " + name);
        }
    }

    private static boolean hasControlCharacter(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    static final class UncheckedHttpException extends RuntimeException {
        UncheckedHttpException(IOException cause) {
            super(cause);
        }
    }
}
