package corvo.core;

import corvo.http.HttpMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Router {

    private final List<RouteEntry> routes = new ArrayList<>();
    private boolean sorted = true;

    public Router register(Class<?> controller) {
        String base = basePath(controller);
        for (Method action : controller.getDeclaredMethods()) {
            Get get = action.getAnnotation(Get.class);
            Post post = action.getAnnotation(Post.class);
            Put put = action.getAnnotation(Put.class);
            Patch patch = action.getAnnotation(Patch.class);
            Delete delete = action.getAnnotation(Delete.class);

            if (get != null) {
                add(entry(HttpMethod.GET, base, get.value(), controller, action));
            }
            if (post != null) {
                add(entry(HttpMethod.POST, base, post.value(), controller, action));
            }
            if (put != null) {
                add(entry(HttpMethod.PUT, base, put.value(), controller, action));
            }
            if (patch != null) {
                add(entry(HttpMethod.PATCH, base, patch.value(), controller, action));
            }
            if (delete != null) {
                add(entry(HttpMethod.DELETE, base, delete.value(), controller, action));
            }
        }
        return this;
    }

    public Router on(HttpMethod verb, String pattern, Endpoint endpoint) {
        add(new RouteEntry(verb, RoutePattern.of(pattern), null, null, endpoint));
        return this;
    }

    public Router get(String pattern, Endpoint endpoint) {
        return on(HttpMethod.GET, pattern, endpoint);
    }

    public Router post(String pattern, Endpoint endpoint) {
        return on(HttpMethod.POST, pattern, endpoint);
    }

    public Router put(String pattern, Endpoint endpoint) {
        return on(HttpMethod.PUT, pattern, endpoint);
    }

    public Router delete(String pattern, Endpoint endpoint) {
        return on(HttpMethod.DELETE, pattern, endpoint);
    }

    private static final Match SIN_METODO = new Match(null, Map.of());

    public Match resolve(HttpMethod verb, String path) {
        ensureSorted();
        // Se parte una vez y se prueba ya partido contra cada ruta candidata.
        String[] parts = RoutePattern.parts(path);
        boolean pathExists = false;
        for (RouteEntry route : routes) {
            if (!route.pattern().matches(parts)) {
                continue;
            }
            pathExists = true;
            if (route.verb() == verb || (verb == HttpMethod.HEAD && route.verb() == HttpMethod.GET)) {
                return new Match(route, route.pattern().variables(parts));
            }
        }
        return pathExists ? SIN_METODO : null;
    }

    public Set<HttpMethod> allowedFor(String path) {
        ensureSorted();
        String[] parts = RoutePattern.parts(path);
        Set<HttpMethod> allowed = EnumSet.noneOf(HttpMethod.class);
        for (RouteEntry route : routes) {
            if (route.pattern().matches(parts)) {
                allowed.add(route.verb());
            }
        }
        if (allowed.contains(HttpMethod.GET)) {
            allowed.add(HttpMethod.HEAD);
        }
        return allowed;
    }

    public List<RouteEntry> routes() {
        ensureSorted();
        return List.copyOf(routes);
    }

    public int size() {
        return routes.size();
    }

    private static RouteEntry entry(HttpMethod verb, String base, String declared,
                                    Class<?> controller, Method action) {
        return new RouteEntry(verb, RoutePattern.of(join(base, declared)), controller, action);
    }

    private void add(RouteEntry route) {
        for (RouteEntry existing : routes) {
            if (existing.verb() == route.verb() && existing.pattern().raw().equals(route.pattern().raw())) {
                throw new IllegalStateException("ruta duplicada: " + route.verb() + " " + route.pattern().raw());
            }
        }
        routes.add(route);
        sorted = false;
    }

    private void ensureSorted() {
        if (!sorted) {
            routes.sort((left, right) -> left.pattern().compareTo(right.pattern()));
            sorted = true;
        }
    }

    private static String basePath(Class<?> controller) {
        Route mapping = controller.getAnnotation(Route.class);
        if (mapping != null && !mapping.value().isEmpty()) {
            return mapping.value();
        }
        String name = controller.getSimpleName();
        if (name.endsWith("Controller")) {
            name = name.substring(0, name.length() - "Controller".length());
        }
        return "/" + name.toLowerCase();
    }

    private static String join(String base, String path) {
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String right = path.isEmpty() ? "" : path.startsWith("/") ? path : "/" + path;
        String joined = left + right;
        return joined.isEmpty() ? "/" : joined;
    }

    public record Match(RouteEntry route, Map<String, String> pathVariables) {

        public boolean methodNotAllowed() {
            return route == null;
        }
    }
}
