package corvo.core;

import corvo.http.Handler;
import corvo.http.HttpException;
import corvo.http.HttpMethod;
import corvo.http.Request;
import corvo.http.Response;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

final class Dispatcher implements Handler {

    private final Router router;
    private final Registry registry;
    private final List<Middleware> middleware;
    private final Authenticator authenticator;
    private final ViewRenderer views;
    private final List<Recovery> recoveries;
    private final Map<Class<?>, Object> controllers = new ConcurrentHashMap<>();

    /** Armada una sola vez: la terminal lee la ruta del contexto en lugar de capturarla. */
    private final Middleware.Chain chain;

    private final Messages messages;

    Dispatcher(Router router, Registry registry, List<Middleware> middleware,
               Authenticator authenticator, ViewRenderer views, Messages messages) {
        this.messages = messages;
        this.router = router;
        this.registry = registry;
        this.middleware = List.copyOf(middleware);
        this.authenticator = authenticator;
        this.views = views;
        this.recoveries = collectRecoveries(router);
        this.chain = buildChain();
    }

    @Override
    public void handle(Request request, Response response) {
        Router.Match match = router.resolve(request.method(), request.path());
        Context context = new Context(request, response,
                match == null ? Map.of() : match.pathVariables(),
                match == null ? null : match.route(),
                match != null && match.methodNotAllowed());
        context.messages(messages);
        Current.enter(context);
        try {
            if (authenticator != null && match != null && !match.methodNotAllowed()) {
                context.principal(authenticator.authenticate(context));
            }
            Object outcome = chain.proceed(context);
            // Un middleware puede cortocircuitar y devolver lo suyo sin llegar a la terminal;
            // eso todavía hay que pintarlo. Si ya se pintó, render() sale solo.
            render(outcome, context);
        } catch (Throwable failure) {
            recover(failure, context);
        } finally {
            // Sin esto, un hilo reutilizado se llevaría el contexto de la petición anterior.
            Current.exit();
        }
    }

    private Middleware.Chain buildChain() {
        Middleware.Chain terminal = ctx -> {
            if (ctx.methodNotAllowed()) {
                ctx.response().header("Allow", allowHeader(ctx.path()));
                throw new HttpException(405, "método no permitido en " + ctx.path());
            }
            if (ctx.route() == null) {
                throw new HttpException(404, "no existe " + ctx.path());
            }
            Object outcome = invoke(ctx.route(), ctx);
            // Pintar dentro de la cadena, no después: si no, un middleware que mide o registra
            // ve un 200 en una petición cuya vista revienta al renderizarse.
            render(outcome, ctx);
            return outcome;
        };
        Middleware.Chain built = terminal;
        for (int i = middleware.size() - 1; i >= 0; i--) {
            Middleware step = middleware.get(i);
            Middleware.Chain next = built;
            built = ctx -> step.handle(ctx, next);
        }
        return built;
    }

    private Object invoke(RouteEntry route, Context context) throws Exception {
        if (route.isLambda()) {
            return route.endpoint().handle(context);
        }
        authorize(route, context);
        Object controller = controllers.computeIfAbsent(route.controller(), registry::create);
        try {
            return route.action().invoke(controller, Binder.argumentos(route.plan(), context));
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception failure) {
                throw failure;
            }
            throw new IllegalStateException(cause);
        }
    }

    private void authorize(RouteEntry route, Context context) {
        if (!route.requiresAuth()) {
            return;
        }
        Principal principal = context.principal();
        if (principal == null) {
            throw new HttpException(401, "se requiere autenticación");
        }
        String[] roles = route.allowedRoles();
        if (roles == null) {
            return;
        }
        for (String allowed : roles) {
            if (principal.hasRole(allowed)) {
                return;
            }
        }
        throw new HttpException(403, "rol insuficiente");
    }

    private void render(Object outcome, Context context) throws Exception {
        Response response = context.response();
        if (response.committed()) {
            return;
        }
        Result result = asResult(outcome);
        response.status(result.statusCode());
        result.extraHeaders().forEach(response::header);

        switch (result.kind()) {
            case TEXT -> response.text(result.payload());
            case HTML -> response.html(result.payload());
            case JSON -> response.json(result.payload());
            case REDIRECT -> response.redirect(result.payload());
            case REDIRECT_EXTERNAL -> response.redirectExternal(result.payload());
            case EMPTY -> response.send(new byte[0]);
            // El Content-Type ya viaja en las cabeceras del Result, puestas más arriba.
            case BINARY -> response.send(result.bytes());
            case VIEW -> {
                if (views == null) {
                    throw new HttpException(501, "no hay motor de vistas configurado");
                }
                // El mapa `t` va como global, no dentro del modelo: así una plantilla escribe
                // {{ t.guardar }} sin que cada controlador se acuerde de meterlo, y olvidarlo
                // en uno solo dejaría esa página sin traducir.
                response.html(views.render(result.payload(), result.model(),
                        messages == null ? java.util.Map.of()
                                         : java.util.Map.of("t", new Textos(messages, context.idioma()))));
            }
        }
    }

    private static Result asResult(Object outcome) {
        return switch (outcome) {
            case null -> Result.noContent();
            case Result result -> result;
            case CharSequence text -> Result.text(text.toString());
            default -> Result.json(outcome);
        };
    }

    private void recover(Throwable failure, Context context) {
        if (context.response().committed()) {
            return;
        }
        for (Recovery recovery : recoveries) {
            if (recovery.type().isInstance(failure)) {
                try {
                    Object controller = controllers.computeIfAbsent(recovery.controller(), registry::create);
                    recovery.action().setAccessible(true);
                    render(recovery.action().invoke(controller, recoveryArguments(recovery, failure, context)),
                            context);
                    return;
                } catch (Exception ignored) {
                    break;
                }
            }
        }
        int status = failure instanceof HttpException http ? http.status() : 500;
        String message = status == 500 ? "error interno" : failure.getMessage();
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", corvo.http.HttpStatus.reason(status));
        body.put("message", message == null ? "" : message);
        body.put("path", context.path());
        if (failure instanceof ValidationException invalid) {
            body.put("fields", invalid.problems());
        }
        try {
            context.response().status(status).type("application/json");
            context.response().send(Json.write(body));
        } catch (RuntimeException ignored) {
        }
    }

    private static Object[] recoveryArguments(Recovery recovery, Throwable failure, Context context) {
        Class<?>[] parameters = recovery.action().getParameterTypes();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            arguments[i] = parameters[i] == Context.class ? context
                    : parameters[i].isInstance(failure) ? failure : null;
        }
        return arguments;
    }

    private String allowHeader(String path) {
        StringJoiner joined = new StringJoiner(", ");
        for (HttpMethod verb : router.allowedFor(path)) {
            joined.add(verb.name());
        }
        return joined.toString();
    }

    private static List<Recovery> collectRecoveries(Router router) {
        List<Recovery> found = new ArrayList<>();
        for (RouteEntry route : router.routes()) {
            if (route.isLambda()) {
                continue;
            }
            for (Method method : route.controller().getDeclaredMethods()) {
                OnError marker = method.getAnnotation(OnError.class);
                if (marker != null && found.stream().noneMatch(item -> item.action().equals(method))) {
                    found.add(new Recovery(marker.value(), route.controller(), method));
                }
            }
        }
        found.sort((left, right) -> left.type().isAssignableFrom(right.type()) ? 1 : -1);
        return found;
    }

    private record Recovery(Class<? extends Throwable> type, Class<?> controller, Method action) {
    }
}
