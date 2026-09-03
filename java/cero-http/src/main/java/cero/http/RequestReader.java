package cero.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

final class RequestReader {

    private static final InputStream EMPTY = new ByteArrayInputStream(new byte[0]);

    private final ByteReader reader;
    private final ServerContext context;
    private final ServerOptions options;

    RequestReader(ByteReader reader, ServerContext context) {
        this.reader = reader;
        this.context = context;
        this.options = context.options();
    }

    IncomingRequest read(String remoteAddress) throws IOException {
        String line = reader.readLine(options.maxRequestLineBytes());
        while (line != null && line.isEmpty()) {
            line = reader.readLine(options.maxRequestLineBytes());
        }
        if (line == null) {
            return null;
        }

        int firstSpace = line.indexOf(' ');
        int secondSpace = firstSpace < 0 ? -1 : line.indexOf(' ', firstSpace + 1);
        if (firstSpace <= 0 || secondSpace <= firstSpace) {
            throw new HttpException(400, "línea de petición inválida");
        }

        HttpMethod method = HttpMethod.of(line.substring(0, firstSpace));
        String target = line.substring(firstSpace + 1, secondSpace);
        String protocol = line.substring(secondSpace + 1);

        if (!protocol.equals("HTTP/1.1") && !protocol.equals("HTTP/1.0")) {
            throw new HttpException(505, "versión no soportada: " + protocol);
        }
        Headers headers;
        String origen;

        if (target.equals("*")) {
            // asterisk-form: solo para preguntar por el servidor entero. RFC 9112 §3.2.4.
            if (method != HttpMethod.OPTIONS) {
                throw new HttpException(400, "el destino * solo vale para OPTIONS");
            }
            headers = readHeaders();
            origen = "*";
        } else if (esAbsoluta(target)) {
            // absolute-form: un servidor de origen tiene que aceptarla, y la autoridad que trae
            // manda sobre la cabecera Host. RFC 9112 §3.2.2 y §3.2.
            int barra = target.indexOf('/', target.indexOf("//") + 2);
            String autoridad = barra < 0
                    ? target.substring(target.indexOf("//") + 2)
                    : target.substring(target.indexOf("//") + 2, barra);
            origen = barra < 0 ? "/" : target.substring(barra);
            headers = readHeaders();
            headers.set("Host", autoridad);
        } else if (target.charAt(0) != '/') {
            throw new HttpException(400, "destino inválido: " + target);
        } else {
            origen = target;
            headers = readHeaders();
        }

        int mark = origen.indexOf('?');
        String path = mark < 0 ? origen : origen.substring(0, mark);
        String rawQuery = mark < 0 ? null : origen.substring(mark + 1);

        requireHost(headers, protocol);
        InputStream body = openBody(method, headers);

        return new IncomingRequest(method, path.equals("*") ? "*" : Url.decodePath(path),
                rawQuery, protocol, headers, body, remoteAddress, context);
    }

    private void requireHost(Headers headers, String protocol) {
        if (!options.requireHost()) {
            return;
        }
        int count = headers.all("Host").size();
        if (protocol.equals("HTTP/1.1") && count != 1) {
            throw new HttpException(400, count == 0 ? "falta la cabecera Host" : "cabecera Host duplicada");
        }
        if (count > 1) {
            throw new HttpException(400, "cabecera Host duplicada");
        }
    }

    private Headers readHeaders() throws IOException {
        Headers headers = new Headers();
        int budget = options.maxHeaderBytes();

        while (true) {
            String line = reader.readLine(Math.min(budget, options.maxHeaderBytes()));
            if (line == null) {
                throw new HttpException(400, "cabeceras incompletas");
            }
            if (line.isEmpty()) {
                return headers;
            }
            budget -= line.length() + 2;
            if (budget <= 0) {
                throw new HttpException(431, "cabeceras demasiado grandes");
            }
            if (headers.size() >= options.maxHeaderCount()) {
                throw new HttpException(431, "demasiadas cabeceras");
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new HttpException(400, "cabecera plegada no permitida");
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpException(400, "cabecera inválida");
            }
            String name = line.substring(0, colon);
            if (!isToken(name)) {
                throw new HttpException(400, "nombre de cabecera inválido: " + name);
            }
            String value = line.substring(colon + 1).trim();
            if (tieneControl(value)) {
                throw new HttpException(400, "valor de cabecera con caracteres de control: " + name);
            }
            headers.add(name, value);
        }
    }

    private static boolean esAbsoluta(String target) {
        return target.regionMatches(true, 0, "http://", 0, 7)
                || target.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * {@code chunked} tiene que ser la última codificación aplicada; si no, no hay forma de saber
     * dónde acaba el mensaje. RFC 9112 §6.1.
     */
    private static boolean chunkedAlFinal(Headers headers) {
        String declarado = null;
        for (String valor : headers.all("Transfer-Encoding")) {
            declarado = declarado == null ? valor : declarado + "," + valor;
        }
        if (declarado == null) {
            return false;
        }
        String[] codificaciones = declarado.split(",");
        String ultima = codificaciones[codificaciones.length - 1].trim();
        if (!ultima.equalsIgnoreCase("chunked")) {
            throw new HttpException(400, "chunked debe ser la última codificación de transferencia");
        }
        for (int i = 0; i < codificaciones.length - 1; i++) {
            if (codificaciones[i].trim().equalsIgnoreCase("chunked")) {
                throw new HttpException(400, "chunked declarado dos veces");
            }
        }
        return true;
    }

    private InputStream openBody(HttpMethod method, Headers headers) {
        boolean chunked = chunkedAlFinal(headers);
        boolean sized = headers.has("Content-Length");

        if (chunked && sized) {
            throw new HttpException(400, "Content-Length y Transfer-Encoding a la vez");
        }
        if (chunked) {
            return new ChunkedBody(reader, options.maxBodyBytes(), options.maxRequestLineBytes());
        }
        if (!sized) {
            return EMPTY;
        }
        if (headers.all("Content-Length").size() > 1) {
            throw new HttpException(400, "Content-Length duplicado");
        }

        long length;
        try {
            length = Long.parseLong(headers.get("Content-Length").trim());
        } catch (NumberFormatException cause) {
            throw new HttpException(400, "Content-Length inválido");
        }
        if (length < 0) {
            throw new HttpException(400, "Content-Length negativo");
        }
        if (length > options.maxBodyBytes()) {
            throw new HttpException(413, "cuerpo demasiado grande");
        }
        if (length > 0 && !method.allowsBody()) {
            throw new HttpException(400, "cuerpo no permitido en " + method);
        }
        return length == 0 ? EMPTY : new FixedBody(reader, length);
    }

    /** Un valor de cabecera solo admite VCHAR, obs-text, espacio y tabulador. RFC 9110 §5.5. */
    private static boolean tieneControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean isToken(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == ':' || c == '(' || c == ')' || c == ',' || c == '/'
                    || c == ';' || c == '<' || c == '=' || c == '>' || c == '?' || c == '@'
                    || c == '[' || c == '\\' || c == ']' || c == '{' || c == '}' || c == '"') {
                return false;
            }
        }
        return true;
    }
}
