package lux.core;

import lux.http.HttpException;
import lux.http.Part;
import lux.http.Request;
import lux.http.Response;
import lux.http.Session;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * Decide de dónde sale cada argumento de una acción. La decisión se toma una vez, al registrar la
 * ruta; en cada petición solo se recorre el plan ya hecho. Es además el paso previo a resolverlo
 * en tiempo de compilación, que es lo que la fase 3 necesitará en Rust y C++.
 */
final class Binder {

    /** De dónde sale un argumento. Ya sabe su nombre y su tipo; solo necesita el contexto. */
    interface Fuente {
        Object valor(Context contexto);
    }

    private Binder() {
    }

    static Fuente[] plan(Method action) {
        Parameter[] parameters = action.getParameters();
        Fuente[] plan = new Fuente[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            plan[i] = fuenteDe(parameters[i]);
        }
        return plan;
    }

    static Object[] argumentos(Fuente[] plan, Context context) {
        if (plan.length == 0) {
            return SIN_ARGUMENTOS;
        }
        Object[] arguments = new Object[plan.length];
        for (int i = 0; i < plan.length; i++) {
            arguments[i] = plan[i].valor(context);
        }
        return arguments;
    }

    private static final Object[] SIN_ARGUMENTOS = new Object[0];

    private static Fuente fuenteDe(Parameter parameter) {
        Class<?> type = parameter.getType();

        if (type == Context.class) {
            return contexto -> contexto;
        }
        if (type == Request.class) {
            return Context::request;
        }
        if (type == Response.class) {
            return Context::response;
        }
        if (type == Session.class) {
            return Context::session;
        }
        if (type == Principal.class) {
            return Context::principal;
        }

        Path path = parameter.getAnnotation(Path.class);
        if (path != null) {
            String nombre = path.value();
            return contexto -> convert(contexto.pathVariable(nombre), type, nombre, true);
        }

        Query query = parameter.getAnnotation(Query.class);
        if (query != null) {
            String nombre = query.value();
            String porDefecto = query.orElse().isEmpty() ? null : query.orElse();
            return contexto -> {
                String raw = contexto.query(nombre);
                return convert(raw == null ? porDefecto : raw, type, nombre, false);
            };
        }

        Form form = parameter.getAnnotation(Form.class);
        if (form != null) {
            String nombre = form.value();
            String porDefecto = form.orElse().isEmpty() ? null : form.orElse();
            return contexto -> {
                String raw = contexto.form(nombre);
                return convert(raw == null ? porDefecto : raw, type, nombre, false);
            };
        }

        Header header = parameter.getAnnotation(Header.class);
        if (header != null) {
            String nombre = header.value();
            return contexto -> convert(contexto.header(nombre), type, nombre, false);
        }

        CookieValue cookie = parameter.getAnnotation(CookieValue.class);
        if (cookie != null) {
            String nombre = cookie.value();
            return contexto -> convert(contexto.cookie(nombre), type, nombre, false);
        }

        if (parameter.isAnnotationPresent(Body.class)) {
            if (type == String.class) {
                return Context::bodyText;
            }
            if (type == byte[].class) {
                return Context::bodyBytes;
            }
            boolean validar = parameter.isAnnotationPresent(Valid.class);
            return contexto -> {
                Object body = contexto.body(type);
                if (validar) {
                    Validation.check(body);
                }
                return body;
            };
        }

        String nombre = parameter.getName();

        if (type == Part.class) {
            return contexto -> contexto.part(nombre);
        }

        boolean primitivo = type.isPrimitive();
        return contexto -> {
            String valor = contexto.pathVariable(nombre);
            if (valor == null) {
                valor = contexto.query(nombre);
            }
            if (valor != null) {
                return convert(valor, type, nombre, false);
            }
            if (primitivo) {
                throw new HttpException(400, "falta el parámetro " + nombre);
            }
            return null;
        };
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
