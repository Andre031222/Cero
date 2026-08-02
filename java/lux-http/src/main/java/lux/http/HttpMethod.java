package lux.http;

public enum HttpMethod {

    GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE;

    public boolean allowsBody() {
        return this != GET && this != HEAD && this != TRACE;
    }

    static HttpMethod of(String name) {
        return switch (name) {
            case "GET" -> GET;
            case "HEAD" -> HEAD;
            case "POST" -> POST;
            case "PUT" -> PUT;
            case "PATCH" -> PATCH;
            case "DELETE" -> DELETE;
            case "OPTIONS" -> OPTIONS;
            case "TRACE" -> TRACE;
            default -> throw new HttpException(501, "método no soportado: " + name);
        };
    }
}
