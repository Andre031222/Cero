package lux.core;

import lux.http.HttpMethod;

import java.lang.reflect.Method;

public record RouteEntry(
        HttpMethod verb,
        RoutePattern pattern,
        Class<?> controller,
        Method action,
        Endpoint endpoint) {

    RouteEntry(HttpMethod verb, RoutePattern pattern, Class<?> controller, Method action) {
        this(verb, pattern, controller, action, null);
    }

    public boolean isLambda() {
        return endpoint != null;
    }

    public String describe() {
        return verb + " " + pattern.raw() + (isLambda() ? "" : " → "
                + controller.getSimpleName() + "." + action.getName());
    }
}
