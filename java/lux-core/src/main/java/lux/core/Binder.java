package lux.core;

import lux.http.HttpException;
import lux.http.Part;
import lux.http.Request;
import lux.http.Response;
import lux.http.Session;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

final class Binder {

    private Binder() {
    }

    static Object[] argumentsFor(Method action, Context context) {
        Parameter[] parameters = action.getParameters();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            arguments[i] = resolve(parameters[i], context);
        }
        return arguments;
    }

    private static Object resolve(Parameter parameter, Context context) {
        Class<?> type = parameter.getType();

        if (type == Context.class) {
            return context;
        }
        if (type == Request.class) {
            return context.request();
        }
        if (type == Response.class) {
            return context.response();
        }
        if (type == Session.class) {
            return context.session();
        }
        if (type == Principal.class) {
            return context.principal();
        }

        Path path = parameter.getAnnotation(Path.class);
        if (path != null) {
            return convert(context.pathVariable(path.value()), type, path.value(), true);
        }

        Query query = parameter.getAnnotation(Query.class);
        if (query != null) {
            String raw = context.query(query.value());
            if (raw == null && !query.orElse().isEmpty()) {
                raw = query.orElse();
            }
            return convert(raw, type, query.value(), false);
        }

        Header header = parameter.getAnnotation(Header.class);
        if (header != null) {
            return convert(context.header(header.value()), type, header.value(), false);
        }

        CookieValue cookie = parameter.getAnnotation(CookieValue.class);
        if (cookie != null) {
            return convert(context.cookie(cookie.value()), type, cookie.value(), false);
        }

        if (parameter.isAnnotationPresent(Body.class)) {
            if (type == String.class) {
                return context.bodyText();
            }
            if (type == byte[].class) {
                return context.bodyBytes();
            }
            Object body = context.body(type);
            if (parameter.isAnnotationPresent(Valid.class)) {
                Validation.check(body);
            }
            return body;
        }

        if (type == Part.class) {
            return context.part(parameter.getName());
        }

        String byName = context.pathVariable(parameter.getName());
        if (byName == null) {
            byName = context.query(parameter.getName());
        }
        if (byName != null) {
            return convert(byName, type, parameter.getName(), false);
        }
        if (type.isPrimitive()) {
            throw new HttpException(400, "falta el parámetro " + parameter.getName());
        }
        return null;
    }

    private static Object convert(String raw, Class<?> type, String name, boolean required) {
        if (raw == null) {
            if (required || type.isPrimitive()) {
                throw new HttpException(400, "falta el parámetro " + name);
            }
            return null;
        }
        if (type == String.class) {
            return raw;
        }
        try {
            return Json.bind(raw, type);
        } catch (RuntimeException cause) {
            throw new HttpException(400, "parámetro inválido " + name + ": " + raw);
        }
    }
}
