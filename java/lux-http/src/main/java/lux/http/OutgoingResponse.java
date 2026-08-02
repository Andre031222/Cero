package lux.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class OutgoingResponse implements Response {

    private static final byte[] NO_BODY = new byte[0];

    private final OutputStream target;
    private final Headers headers = new Headers();
    private final boolean headOnly;

    private int status = 200;
    private boolean committed;
    private boolean keepAlive = true;
    private ChunkedOutput chunked;

    OutgoingResponse(OutputStream target, boolean headOnly) {
        this.target = target;
        this.headOnly = headOnly;
    }

    @Override
    public Response status(int code) {
        if (committed) {
            throw new IllegalStateException("respuesta ya enviada");
        }
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
        if (committed) {
            throw new IllegalStateException("respuesta ya enviada");
        }
        headers.set(name, value);
        return this;
    }

    @Override
    public Response type(String contentType) {
        return header("Content-Type", contentType);
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
        if (committed) {
            throw new IllegalStateException("respuesta ya enviada");
        }
        committed = true;
        try {
            writeHead(-1, true);
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

    boolean keepAlive() {
        return keepAlive;
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

    private void typeIfAbsent(String contentType) {
        if (!headers.has("Content-Type")) {
            headers.set("Content-Type", contentType);
        }
    }

    private void commit(byte[] body) {
        if (committed) {
            throw new IllegalStateException("respuesta ya enviada");
        }
        committed = true;
        boolean withBody = HttpStatus.allowsBody(status);
        try {
            writeHead(withBody ? body.length : -1, false);
            if (withBody && !headOnly && body.length > 0) {
                target.write(body);
            }
            target.flush();
        } catch (IOException cause) {
            throw new UncheckedHttpException(cause);
        }
    }

    private void writeHead(long contentLength, boolean useChunked) throws IOException {
        StringBuilder head = new StringBuilder(160);
        head.append("HTTP/1.1 ").append(status).append(' ').append(HttpStatus.reason(status)).append("\r\n");
        head.append("Date: ").append(HttpDate.now()).append("\r\n");

        if (useChunked) {
            head.append("Transfer-Encoding: chunked\r\n");
        } else if (contentLength >= 0) {
            head.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");

        for (int i = 0; i < headers.size(); i++) {
            head.append(headers.name(i)).append(": ").append(headers.value(i)).append("\r\n");
        }
        head.append("\r\n");

        target.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    static final class UncheckedHttpException extends RuntimeException {
        UncheckedHttpException(IOException cause) {
            super(cause);
        }
    }
}
