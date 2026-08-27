package corvo.core;

import corvo.http.HttpMethod;
import corvo.http.Part;
import corvo.http.Request;
import corvo.http.Response;
import corvo.http.Session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Context {

    private final Request request;
    private final Response response;
    private final Map<String, String> pathVariables;
    private final RouteEntry route;
    private final boolean methodNotAllowed;
    private final Map<String, Object> attributes = new HashMap<>(4);

    private Principal principal;

    Context(Request request, Response response, Map<String, String> pathVariables, RouteEntry route,
            boolean methodNotAllowed) {
        this.request = request;
        this.response = response;
        this.pathVariables = pathVariables;
        this.route = route;
        this.methodNotAllowed = methodNotAllowed;
    }

    /** El camino existe pero no para este verbo. */
    boolean methodNotAllowed() {
        return methodNotAllowed;
    }

    public RouteEntry route() {
        return route;
    }

    public boolean routed() {
        return route != null;
    }

    public Request request() {
        return request;
    }

    public Response response() {
        return response;
    }

    public HttpMethod method() {
        return request.method();
    }

    public String path() {
        return request.path();
    }

    public String pathVariable(String name) {
        return pathVariables.get(name);
    }

    public Map<String, String> pathVariables() {
        return pathVariables;
    }

    public String query(String name) {
        return request.query(name);
    }

    public String query(String name, String fallback) {
        String found = request.query(name);
        return found == null ? fallback : found;
    }

    public List<String> queryAll(String name) {
        return request.queryAll(name);
    }

    public String form(String name) {
        return request.form(name);
    }

    public String form(String name, String fallback) {
        String found = request.form(name);
        return found == null ? fallback : found;
    }

    public List<String> formAll(String name) {
        return request.formAll(name);
    }

    public Map<String, List<String>> forms() {
        return request.forms();
    }

    public String header(String name) {
        return request.header(name);
    }

    public String cookie(String name) {
        return request.cookie(name);
    }

    public Session session() {
        return request.session();
    }

    public Session session(boolean create) {
        return request.session(create);
    }

    public List<Part> parts() {
        return request.parts();
    }

    public Part part(String name) {
        return request.part(name);
    }

    public String field(String name) {
        return request.field(name);
    }

    public String bodyText() {
        return request.bodyText();
    }

    public byte[] bodyBytes() {
        return request.bodyBytes();
    }

    public <T> T body(Class<T> type) {
        return Json.read(request.bodyText(), type);
    }

    public Principal principal() {
        return principal;
    }

    public void principal(Principal value) {
        principal = value;
    }

    public boolean authenticated() {
        return principal != null;
    }

    public Object attribute(String name) {
        return attributes.get(name);
    }

    public Context attribute(String name, Object value) {
        attributes.put(name, value);
        return this;
    }
}
