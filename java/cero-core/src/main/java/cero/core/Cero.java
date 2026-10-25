package cero.core;

import cero.http.ErrorReporter;
import cero.http.Handler;
import cero.http.Server;
import cero.http.ServerOptions;

import java.util.ArrayList;
import java.util.List;

public final class Cero {

    private final Router router = new Router();
    private final Registry registry = new Registry();
    private final List<Middleware> middleware = new ArrayList<>();

    private Config config = Config.empty();
    private Authenticator authenticator;
    private ErrorReporter reporter = ErrorReporter.standardError();
    private ServerOptions options = ServerOptions.defaults();
    private Handler fallback;
    private boolean banner = true;

    private Cero() {
    }

    public static Cero app() {
        return new Cero();
    }

    public static Server run(int port, Class<?>... controllers) {
        return app().port(port).controllers(controllers).start();
    }

    private Messages messages;

    public Cero config(Config value) {
        config = value;
        options = applyConfig(options, value);
        registry.add(Config.class, value);
        return this;
    }

    public Cero loadConfig() {
        return config(Config.load());
    }

    public Cero options(ServerOptions value) {
        options = value;
        return this;
    }

    public Cero port(int value) {
        options = options.toBuilder().port(value).build();
        return this;
    }

    public Cero host(String value) {
        options = options.toBuilder().host(value).build();
        return this;
    }

    public Cero controllers(Class<?>... types) {
        for (Class<?> type : types) {
            router.register(type);
        }
        return this;
    }

    public Cero routes(java.util.function.Consumer<Router> definition) {
        definition.accept(router);
        return this;
    }

    public Cero service(Object instance) {
        registry.add(instance);
        return this;
    }

    public <T> Cero service(Class<?> contract, T instance) {
        registry.add(contract, instance);
        return this;
    }

    public Cero use(Middleware step) {
        middleware.add(step);
        return this;
    }

    public Cero authenticator(Authenticator value) {
        authenticator = value;
        return this;
    }

    public Cero reporter(ErrorReporter value) {
        reporter = value;
        return this;
    }

    public Cero fallback(Handler value) {
        fallback = value;
        return this;
    }

    /**
     * Publica {@code /cero/vivo} y {@code /cero/listo}.
     *
     * <p>Las rutas se registran aquí y no en {@code start()} para que cuenten en el número de
     * rutas del arranque: un endpoint que no aparece donde aparecen los demás es un endpoint que
     * nadie recuerda que existe.
     */
    public Cero health(Health checks) {
        router.get("/cero/vivo", checks.liveEndpoint());
        router.get("/cero/listo", checks.readyEndpoint());
        return this;
    }

    /** Los textos de la aplicación, para {@code context.t(...)}. */
    public Cero messages(Messages value) {
        messages = value;
        return this;
    }

    public Cero quiet() {
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
        return new Dispatcher(router, registry, middleware, authenticator, messages, fallback);
    }

    public Server start() {
        long began = System.nanoTime();
        Handler handler = handler();
        config.revisarEntorno();

        Server server = Server.start(options, handler, reporter);
        if (banner) {
            long millis = (System.nanoTime() - began) / 1_000_000;
            System.out.printf("cero · %s://%s:%d · %d rutas · %d ms%n",
                    options.secure() ? "https" : "http", options.host(), server.port(),
                    router.size(), millis);
        }
        return server;
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
