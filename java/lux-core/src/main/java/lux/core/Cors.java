package lux.core;

import lux.http.HttpException;
import lux.http.HttpMethod;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Cors implements Middleware {

    private static final String ANY = "*";

    private final Set<String> origins;
    private Set<String> methods = new LinkedHashSet<>(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    private Set<String> headers = new LinkedHashSet<>(List.of("Content-Type", "Authorization",
            Csrf.HEADER));
    private Set<String> exposed = new LinkedHashSet<>();
    private boolean credentials;
    private long maxAgeSeconds = 86_400;

    private Cors(Set<String> origins) {
        this.origins = origins;
    }

    public static Cors anyOrigin() {
        return new Cors(new LinkedHashSet<>(List.of(ANY)));
    }

    public static Cors allowing(String... origins) {
        return new Cors(new LinkedHashSet<>(List.of(origins)));
    }

    public Cors methods(String... values) {
        methods = new LinkedHashSet<>(List.of(values));
        return this;
    }

    public Cors headers(String... values) {
        headers = new LinkedHashSet<>(List.of(values));
        return this;
    }

    public Cors expose(String... values) {
        exposed = new LinkedHashSet<>(List.of(values));
        return this;
    }

    public Cors credentials(boolean value) {
        credentials = value;
        return this;
    }

    public Cors maxAge(Duration value) {
        maxAgeSeconds = value.toSeconds();
        return this;
    }

    @Override
    public Object handle(Context context, Chain chain) throws Exception {
        String origin = context.header("Origin");
        boolean preflight = context.method() == HttpMethod.OPTIONS
                && context.header("Access-Control-Request-Method") != null;

        if (origin == null) {
            return chain.proceed(context);
        }
        if (!allowed(origin)) {
            if (preflight) {
                throw new HttpException(403, "origen no permitido: " + origin);
            }
            return chain.proceed(context);
        }

        apply(context, origin);

        if (!preflight) {
            return chain.proceed(context);
        }
        String requested = context.header("Access-Control-Request-Method");
        if (!methods.contains(requested)) {
            throw new HttpException(403, "método no permitido por CORS: " + requested);
        }
        context.response()
                .header("Access-Control-Allow-Methods", String.join(", ", methods))
                .header("Access-Control-Allow-Headers", String.join(", ", headers))
                .header("Access-Control-Max-Age", String.valueOf(maxAgeSeconds));
        return Result.noContent();
    }

    private void apply(Context context, String origin) {
        boolean echo = credentials || !origins.contains(ANY);
        context.response().header("Access-Control-Allow-Origin", echo ? origin : ANY);
        if (echo) {
            context.response().header("Vary", "Origin");
        }
        if (credentials) {
            context.response().header("Access-Control-Allow-Credentials", "true");
        }
        if (!exposed.isEmpty()) {
            context.response().header("Access-Control-Expose-Headers", String.join(", ", exposed));
        }
    }

    private boolean allowed(String origin) {
        return origins.contains(ANY) || origins.contains(origin);
    }
}
