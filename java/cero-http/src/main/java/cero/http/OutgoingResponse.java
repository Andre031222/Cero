package cero.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class OutgoingResponse implements Response {

    private static final byte[] NO_BODY = new byte[0];

    private final OutputStream target;
    private final IncomingRequest request;
    private final ServerOptions options;
    private final Headers headers = new Headers();
    private final AsciiBuffer head;
    private final boolean headOnly;

    private int status = 200;
    private boolean committed;
    private boolean keepAlive = true;
    private boolean upgraded;
    private ChunkedOutput chunked;
    private ByteReader source;

    OutgoingResponse(OutputStream target, IncomingRequest request, ServerOptions options, AsciiBuffer head) {
        this.target = target;
        this.request = request;
        this.options = options;
        this.head = head;
        this.headOnly = request != null && request.method() == HttpMethod.HEAD;
    }

    void source(ByteReader reader) {
        source = reader;
    }

    boolean upgraded() {
        return upgraded;
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
        if (esExterna(location)) {
            throw new HttpException(400, "redirección externa no permitida: " + location
                    + " — usa redirectExternal si es a propósito");
        }
        enviarRedireccion(location);
    }

    @Override
    public void redirectExternal(String location) {
        enviarRedireccion(location);
    }

    private void enviarRedireccion(String location) {
        if (status == 200) {
            status = 302;
        }
        header("Location", location);
        commit(NO_BODY);
    }

    /**
     * Un destino fuera de este sitio: con esquema ({@code https:}, {@code javascript:}) o
     * relativo al protocolo ({@code //otro.host}). Si viene de la entrada del usuario, dejarlo
     * pasar es una redirección abierta.
     */
    static boolean esExterna(String location) {
        if (location == null) {
            return false;
        }
        String limpio = location.trim();
        if (limpio.startsWith("//")) {
            return true;
        }
        int puntos = limpio.indexOf(':');
        if (puntos <= 0) {
            return false;
        }
        for (int i = 0; i < puntos; i++) {
            char c = limpio.charAt(i);
            boolean valido = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!valido) {
                return false;
            }
        }
        return true;
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
    public Duplex switchProtocols() {
        requireOpen();
        if (source == null) {
            throw new IllegalStateException("esta respuesta no tiene una conexión que ceder");
        }
        committed = true;
        upgraded = true;
        keepAlive = false;
        try {
            head.reset();
            head.put("HTTP/1.1 101 ").put(HttpStatus.reason(101)).crlf();
            for (int i = 0; i < headers.size(); i++) {
                rejectControlCharacters(headers.name(i), headers.value(i));
                head.put(headers.name(i)).put(": ").put(headers.value(i)).crlf();
            }
            head.crlf();
            head.writeTo(target);
            target.flush();
        } catch (IOException cause) {
            throw new UncheckedHttpException(cause);
        }
        InputStream entrada = source.asInputStream();
        return new Duplex() {

            @Override
            public InputStream in() {
                return entrada;
            }

            @Override
            public OutputStream out() {
                return target;
            }

            @Override
            public Request request() {
                return request;
            }
        };
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
            // Sin vaciar: lo hace la conexión cuando el handler y su middleware han terminado.
            // Así un registro o una métrica no se quedan a medias detrás de una respuesta que
            // el cliente ya recibió, y se ahorra una llamada al sistema por petición.
        } catch (IOException cause) {
            throw new UncheckedHttpException(cause);
        }
    }

    private void writeHead(long contentLength, boolean useChunked, String encoding) throws IOException {
        head.reset();
        head.put("HTTP/1.1 ").put(status).put(' ').put(HttpStatus.reason(status)).crlf();
        head.put("Date: ").put(HttpDate.now()).crlf();

        if (useChunked) {
            head.put("Transfer-Encoding: chunked").crlf();
        } else if (contentLength >= 0) {
            head.put("Content-Length: ").put(contentLength).crlf();
        }
        if (encoding != null) {
            head.put("Content-Encoding: ").put(encoding).crlf();
            head.put("Vary: Accept-Encoding").crlf();
        }
        head.put("Connection: ").put(keepAlive ? "keep-alive" : "close").crlf();

        if (request != null) {
            Cookie pending = request.pendingSessionCookie();
            if (pending != null) {
                head.put("Set-Cookie: ").put(pending.encode()).crlf();
            }
        }

        for (int i = 0; i < headers.size(); i++) {
            rejectControlCharacters(headers.name(i), headers.value(i));
            head.put(headers.name(i)).put(": ").put(headers.value(i)).crlf();
        }
        head.crlf();

        head.writeTo(target);
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
