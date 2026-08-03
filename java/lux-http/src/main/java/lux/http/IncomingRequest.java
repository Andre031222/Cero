package lux.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class IncomingRequest implements Request {

    private static final int MAX_PARTS = 256;

    private final HttpMethod method;
    private final String path;
    private final String rawQuery;
    private final String protocol;
    private final Headers headers;
    private final InputStream body;
    private final String remoteAddress;
    private final ServerContext context;

    private Map<String, List<String>> params;
    private Map<String, List<String>> fields;
    private Map<String, String> cookies;
    private List<Part> parts;
    private byte[] cachedBody;
    private Sessions.Entry session;
    private boolean sessionIsNew;

    IncomingRequest(HttpMethod method, String path, String rawQuery, String protocol,
                    Headers headers, InputStream body, String remoteAddress, ServerContext context) {
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery;
        this.protocol = protocol;
        this.headers = headers;
        this.body = body;
        this.remoteAddress = remoteAddress;
        this.context = context;
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
    public String form(String name) {
        List<String> found = forms().get(name);
        return found == null || found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<String> formAll(String name) {
        return forms().getOrDefault(name, List.of());
    }

    @Override
    public Map<String, List<String>> forms() {
        if (fields == null) {
            fields = isFormEncoded() ? Url.parseQuery(bodyText()) : Map.of();
        }
        return fields;
    }

    private boolean isFormEncoded() {
        String type = headers.get("Content-Type");
        return type != null && type.toLowerCase().startsWith("application/x-www-form-urlencoded");
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
        // Detrás de un proxy, remoteAddress es siempre el del proxy: sin esto el limitador cuenta
        // a todo el mundo junto y el primero que llegue al tope bloquea el sitio entero. Solo se
        // hace caso a la cabecera si quien nos habla está en la lista de confianza.
        if (!context.options().trustedProxies().contains(remoteAddress)) {
            return remoteAddress;
        }
        String reenviado = headers.get("X-Forwarded-For");
        if (reenviado == null || reenviado.isBlank()) {
            return remoteAddress;
        }
        // El primero de la lista es el cliente original; los siguientes, los saltos intermedios.
        int coma = reenviado.indexOf(',');
        String cliente = (coma < 0 ? reenviado : reenviado.substring(0, coma)).trim();
        return cliente.isEmpty() ? remoteAddress : cliente;
    }

    @Override
    public boolean secure() {
        if (context.options().secure()) {
            return true;
        }
        // Detrás de un proxy inverso el TLS lo termina él y a nosotros nos llega en claro. Solo
        // se cree la cabecera si se ha declarado que hay un proxy: fiarse de ella sin más
        // permitiría a cualquier cliente decir que su petición es segura.
        if (!context.options().behindProxy()) {
            return false;
        }
        String declarado = headers.get("X-Forwarded-Proto");
        if (declarado == null) {
            return false;
        }
        int coma = declarado.indexOf(',');
        return (coma < 0 ? declarado : declarado.substring(0, coma)).trim().equalsIgnoreCase("https");
    }

    @Override
    public String cookie(String name) {
        return cookies().get(name);
    }

    @Override
    public Map<String, String> cookies() {
        if (cookies == null) {
            cookies = parseCookies();
        }
        return cookies;
    }

    @Override
    public Session session() {
        return session(true);
    }

    @Override
    public Session session(boolean create) {
        if (session != null && session.valid()) {
            return session;
        }
        session = context.sessions().find(cookie(Sessions.COOKIE));
        if (session != null) {
            return session;
        }
        if (!create) {
            return null;
        }
        session = context.sessions().create();
        session.markCreated();
        sessionIsNew = true;
        return session;
    }

    @Override
    public List<Part> parts() {
        if (parts == null) {
            String contentType = headers.get("Content-Type");
            if (!Multipart.applies(contentType)) {
                throw new HttpException(415, "la petición no es multipart/form-data");
            }
            parts = Multipart.parse(bodyBytes(), contentType, MAX_PARTS);
        }
        return parts;
    }

    @Override
    public Part part(String name) {
        for (Part part : parts()) {
            if (part.name().equals(name)) {
                return part;
            }
        }
        return null;
    }

    @Override
    public String field(String name) {
        Part found = part(name);
        return found == null ? null : found.text();
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

    Cookie pendingSessionCookie() {
        if (session == null || !session.valid()) {
            return null;
        }
        // Nueva o recién rotada: en los dos casos hay que mandarle al navegador el identificador
        // que toca. Antes solo se emitía al nacer, y por eso rotar no servía de nada.
        if (!sessionIsNew && !session.idRotado()) {
            return null;
        }
        sessionIsNew = false;
        session.cookieEmitida();
        return Cookie.of(Sessions.COOKIE, session.id())
                .httpOnly(true)
                .secure(secure())
                .sameSite("Lax");
    }

    boolean bodyDrained() {
        if (cachedBody != null) {
            return true;
        }
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

    private Map<String, String> parseCookies() {
        Map<String, String> found = new LinkedHashMap<>();
        for (String header : headers.all("Cookie")) {
            for (String pair : header.split(";")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = pair.substring(0, equals).trim();
                String value = pair.substring(equals + 1).trim();
                if (!name.isEmpty()) {
                    found.putIfAbsent(name, value);
                }
            }
        }
        return found;
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
