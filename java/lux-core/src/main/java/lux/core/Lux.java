package lux.core;

import lux.http.ErrorReporter;
import lux.http.Handler;
import lux.http.Server;
import lux.http.ServerOptions;

import java.util.ArrayList;
import java.util.List;

public final class Lux {

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

    private Lux() {
    }

    public static Lux app() {
        return new Lux();
    }

    public static Server run(int port, Class<?>... controllers) {
        return app().port(port).controllers(controllers).start();
    }

    public Lux config(Config value) {
        config = value;
        options = applyConfig(options, value);
        registry.add(Config.class, value);
        return this;
    }

    public Lux loadConfig() {
        return config(Config.load());
    }

    public Lux options(ServerOptions value) {
        options = value;
        return this;
    }

    public Lux port(int value) {
        options = options.toBuilder().port(value).build();
        return this;
    }

    public Lux host(String value) {
        options = options.toBuilder().host(value).build();
        return this;
    }

    public Lux controllers(Class<?>... types) {
        for (Class<?> type : types) {
            router.register(type);
        }
        return this;
    }

    public Lux routes(java.util.function.Consumer<Router> definition) {
        definition.accept(router);
        return this;
    }

    public Lux service(Object instance) {
        registry.add(instance);
        return this;
    }

    public <T> Lux service(Class<?> contract, T instance) {
        registry.add(contract, instance);
        return this;
    }

    public Lux use(Middleware step) {
        middleware.add(step);
        return this;
    }

    public Lux authenticator(Authenticator value) {
        authenticator = value;
        return this;
    }

    public Lux views(ViewRenderer value) {
        views = value;
        return this;
    }

    public Lux reporter(ErrorReporter value) {
        reporter = value;
        return this;
    }

    public Lux fallback(Handler value) {
        fallback = value;
        return this;
    }

    public Lux quiet() {
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

    public Server start() {
        long began = System.nanoTime();
        Handler dispatcher = new Dispatcher(router, registry, middleware, authenticator, views);
        Handler handler = fallback == null ? dispatcher : withFallback(dispatcher);

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
