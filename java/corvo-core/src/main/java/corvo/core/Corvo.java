package corvo.core;

import corvo.http.ErrorReporter;
import corvo.http.Handler;
import corvo.http.Server;
import corvo.http.ServerOptions;

import java.util.ArrayList;
import java.util.List;

public final class Corvo {

    private final Router router = new Router();
    private final Registry registry = new Registry();
    private final List<Middleware> middleware = new ArrayList<>();

    private Config config = Config.empty();
    private Authenticator authenticator;
    private ViewRenderer views;
    private ErrorReporter reporter = ErrorReporter.standardError();
    private ServerOptions options = ServerOptions.defaults();
    private Handler fallback;
    private boolean banner = true;

    private Corvo() {
    }

    public static Corvo app() {
        return new Corvo();
    }

    public static Server run(int port, Class<?>... controllers) {
        return app().port(port).controllers(controllers).start();
    }

    public Corvo config(Config value) {
        config = value;
        options = applyConfig(options, value);
        registry.add(Config.class, value);
        return this;
    }

    public Corvo loadConfig() {
        return config(Config.load());
    }

    public Corvo options(ServerOptions value) {
        options = value;
        return this;
    }

    public Corvo port(int value) {
        options = options.toBuilder().port(value).build();
        return this;
    }

    public Corvo host(String value) {
        options = options.toBuilder().host(value).build();
        return this;
    }

    public Corvo controllers(Class<?>... types) {
        for (Class<?> type : types) {
            router.register(type);
        }
        return this;
    }

    public Corvo routes(java.util.function.Consumer<Router> definition) {
        definition.accept(router);
        return this;
    }

    public Corvo service(Object instance) {
        registry.add(instance);
        return this;
    }

    public <T> Corvo service(Class<?> contract, T instance) {
        registry.add(contract, instance);
        return this;
    }

    public Corvo use(Middleware step) {
        middleware.add(step);
        return this;
    }

    public Corvo authenticator(Authenticator value) {
        authenticator = value;
        return this;
    }

    public Corvo views(ViewRenderer value) {
        views = value;
        return this;
    }

    public Corvo reporter(ErrorReporter value) {
        reporter = value;
        return this;
    }

    public Corvo fallback(Handler value) {
        fallback = value;
        return this;
    }

    public Corvo quiet() {
        banner = false;
        return this;
    }

    public Router router() {
        return router;
    }

    public Registry registry() {
        return registry;
    }

    public Config configuration() {
        return config;
    }

    public Handler handler() {
        Handler dispatcher = new Dispatcher(router, registry, middleware, authenticator, views);
        return fallback == null ? dispatcher : withFallback(dispatcher);
    }

    public Server start() {
        long began = System.nanoTime();
        Handler handler = handler();

        Server server = Server.start(options, handler, reporter);
        if (banner) {
            long millis = (System.nanoTime() - began) / 1_000_000;
            System.out.printf("lux · %s://%s:%d · %d rutas · %d ms%n",
                    options.secure() ? "https" : "http", options.host(), server.port(),
                    router.size(), millis);
        }
        return server;
    }

    private Handler withFallback(Handler dispatcher) {
        return (request, response) -> {
            if (router.resolve(request.method(), request.path()) == null) {
                fallback.handle(request, response);
                return;
            }
            dispatcher.handle(request, response);
        };
    }

    private static ServerOptions applyConfig(ServerOptions current, Config config) {
        ServerOptions.Builder builder = current.toBuilder();
        if (config.has("server.port")) {
            builder.port(config.getInt("server.port", current.port()));
        }
        if (config.has("server.host")) {
            builder.host(config.get("server.host"));
        }
        if (config.has("server.maxConnections")) {
            builder.maxConnections(config.getInt("server.maxConnections", current.maxConnections()));
        }
        if (config.has("server.maxBodyBytes")) {
            builder.maxBodyBytes(config.getLong("server.maxBodyBytes", current.maxBodyBytes()));
        }
        if (config.has("server.idleTimeoutMillis")) {
            builder.idleTimeoutMillis(config.getInt("server.idleTimeoutMillis", current.idleTimeoutMillis()));
        }
        if (config.has("server.behindProxy")) {
            builder.behindProxy(config.getBoolean("server.behindProxy", false));
        }
        if (config.has("server.handlerTimeoutMillis")) {
            builder.handlerTimeoutMillis(config.getInt("server.handlerTimeoutMillis",
                    current.handlerTimeoutMillis()));
        }
        if (config.has("server.gzipMinBytes")) {
            builder.gzipMinBytes(config.getInt("server.gzipMinBytes", current.gzipMinBytes()));
        }
        if (config.has("server.sessionTimeoutMillis")) {
            builder.sessionTimeoutMillis(config.getInt("server.sessionTimeoutMillis",
                    current.sessionTimeoutMillis()));
        }
        return builder.build();
    }
}
