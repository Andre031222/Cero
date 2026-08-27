package corvo.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Result {

    public enum Kind { TEXT, HTML, JSON, VIEW, REDIRECT,
        REDIRECT_EXTERNAL, EMPTY, BINARY }

    private final Kind kind;
    private final String payload;
    private final Object model;
    private final byte[] bytes;

    private int status;
    private Map<String, String> headers;

    private Result(Kind kind, String payload, Object model, int status) {
        this(kind, payload, model, status, null);
    }

    private Result(Kind kind, String payload, Object model, int status, byte[] bytes) {
        this.kind = kind;
        this.payload = payload;
        this.model = model;
        this.status = status;
        this.bytes = bytes;
    }

    public static Result text(String body) {
        return new Result(Kind.TEXT, body, null, 200);
    }

    public static Result html(String body) {
        return new Result(Kind.HTML, body, null, 200);
    }

    public static Result json(Object value) {
        return new Result(Kind.JSON, Json.write(value), value, 200);
    }

    public static Result raw(String rawJson) {
        return new Result(Kind.JSON, rawJson, null, 200);
    }

    public static Result view(String template, Object model) {
        return new Result(Kind.VIEW, template, model, 200);
    }

    public static Result redirect(String location) {
        return new Result(Kind.REDIRECT, location, null, 302);
    }

    /** Redirección fuera del sitio, declarada a propósito. */
    public static Result redirectExternal(String location) {
        return new Result(Kind.REDIRECT_EXTERNAL, location, null, 302);
    }

    public static Result created(Object value) {
        return json(value).status(201);
    }

    public static Result noContent() {
        return new Result(Kind.EMPTY, "", null, 204);
    }

    /**
     * Bytes tal cual, con su tipo. Para lo que no es texto: un PDF, una imagen, un zip armado
     * al vuelo.
     *
     * <p>Existe porque devolver binario como {@code String} lo rompe en silencio: el cuerpo se
     * escribe en UTF-8 y todo byte por encima de 0x7F sale convertido en otra cosa. El archivo
     * llega, pesa parecido y no abre.
     */
    public static Result bytes(byte[] body, String contentType) {
        return new Result(Kind.BINARY, "", null, 200, body == null ? new byte[0] : body)
                .header("Content-Type", contentType);
    }

    /**
     * Bytes que el navegador debe guardar en vez de mostrar, con el nombre de archivo puesto.
     *
     * <p>El nombre se limpia antes de entrar en la cabecera: unas comillas o un salto de línea
     * en un nombre que venga de fuera partirían la respuesta en dos e inyectarían cabeceras.
     */
    public static Result download(byte[] body, String filename, String contentType) {
        return bytes(body, contentType)
                .header("Content-Disposition", "attachment; filename=\"" + sanear(filename) + "\"");
    }

    /** Deja solo lo que puede ir sin peligro dentro de unas comillas en una cabecera. */
    private static String sanear(String filename) {
        if (filename == null || filename.isBlank()) {
            return "descarga";
        }
        StringBuilder limpio = new StringBuilder(filename.length());
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            boolean seguro = c >= ' ' && c < 127 && c != '"' && c != '\\' && c != '/';
            limpio.append(seguro ? c : '_');
        }
        return limpio.toString();
    }

    public static Result status(int code, String body) {
        return text(body).status(code);
    }

    public Result status(int code) {
        status = code;
        return this;
    }

    public Result header(String name, String value) {
        if (headers == null) {
            headers = new LinkedHashMap<>();
        }
        headers.put(name, value);
        return this;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * El cuerpo binario, o {@code null} si este resultado no lo es.
     *
     * <p>Devuelve el array de dentro, sin copiar. Copiar aquí duplicaría en memoria cada archivo
     * que se sirva, y quien recibe esto es el escritor de la respuesta, que solo lee.
     */
    public byte[] bytes() {
        return bytes;
    }

    public String payload() {
        return payload;
    }

    public Object model() {
        return model;
    }

    public int statusCode() {
        return status;
    }

    public Map<String, String> extraHeaders() {
        return headers == null ? Map.of() : headers;
    }
}
