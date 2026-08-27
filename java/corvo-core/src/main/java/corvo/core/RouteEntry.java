package corvo.core;

import corvo.http.HttpMethod;

import java.lang.reflect.Method;

/**
 * Una ruta registrada. Lo que la reflexión puede decir de ella —accesibilidad, anotaciones de
 * autorización, de dónde sale cada argumento— se resuelve al registrarla, no en cada petición.
 */
public final class RouteEntry {

    private final HttpMethod verb;
    private final RoutePattern pattern;
    private final Class<?> controller;
    private final Method action;
    private final Endpoint endpoint;

    private final boolean requiresAuth;
    private final String[] allowedRoles;
    private final Binder.Fuente[] plan;

    RouteEntry(HttpMethod verb, RoutePattern pattern, Class<?> controller, Method action, Endpoint endpoint) {
        this.verb = verb;
        this.pattern = pattern;
        this.controller = controller;
        this.action = action;
        this.endpoint = endpoint;

        if (action == null) {
            this.requiresAuth = false;
            this.allowedRoles = null;
            this.plan = null;
            return;
        }
        action.setAccessible(true);
        RequireRole role = action.getAnnotation(RequireRole.class) != null
                ? action.getAnnotation(RequireRole.class)
                : controller.getAnnotation(RequireRole.class);
        this.allowedRoles = role == null ? null : role.value().clone();
        this.requiresAuth = action.isAnnotationPresent(RequireAuth.class)
                || controller.isAnnotationPresent(RequireAuth.class);
        this.plan = Binder.plan(action);
    }

    RouteEntry(HttpMethod verb, RoutePattern pattern, Class<?> controller, Method action) {
        this(verb, pattern, controller, action, null);
    }

    public HttpMethod verb() {
        return verb;
    }

    public RoutePattern pattern() {
        return pattern;
    }

    public Class<?> controller() {
        return controller;
    }

    public Method action() {
        return action;
    }

    public Endpoint endpoint() {
        return endpoint;
    }

    public boolean isLambda() {
        return endpoint != null;
    }

    /** ¿Exige un usuario autenticado, por {@code @RequireAuth} o por exigir un rol? */
    boolean requiresAuth() {
        return requiresAuth || allowedRoles != null;
    }

    /** Roles admitidos, o {@code null} si la ruta no exige ninguno en concreto. */
    String[] allowedRoles() {
        return allowedRoles;
    }

    Binder.Fuente[] plan() {
        return plan;
    }

    public String describe() {
        return verb + " " + pattern.raw() + (isLambda() ? "" : " → "
                + controller.getSimpleName() + "." + action.getName());
    }

    @Override
    public String toString() {
        return describe();
    }
}
