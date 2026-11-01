package cero.core;

import cero.http.Handler;
import cero.http.HttpException;
import cero.http.HttpMethod;
import cero.http.Request;
import cero.http.Response;

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
    private final List<Recovery> recoveries;
    private final Map<Class<?>, Object> controllers = new ConcurrentHashMap<>();

    private final Middleware.Chain chain;

    private final Messages messages;

    private final Handler fallback;

    Dispatcher(Router router, Registry registry, List<Middleware> middleware,
               Authenticator authenticator, Messages messages,
               Handler fallback) {
        this.messages = messages;
        this.fallback = fallback;
        this.router = router;
        this.registry = registry;
        this.middleware = List.copyOf(middleware);
        this.authenticator = authenticator;
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
            render(outcome, context);
        } catch (Throwable failure) {
            recover(failure, context);
        } finally {
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
                if (fallback == null) {
                    throw new HttpException(404, "no existe " + ctx.path());
                }
                fallback.handle(ctx.request(), ctx.response());
                return null;
            }
            Object outcome = invoke(ctx.route(), ctx);
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
            case BINARY -> response.send(result.bytes());
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
        body.put("error", cero.http.HttpStatus.reason(status));
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
