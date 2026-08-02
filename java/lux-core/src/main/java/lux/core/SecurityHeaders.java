package lux.core;

public final class SecurityHeaders implements Middleware {

    private String frameOptions = "DENY";
    private String referrerPolicy = "strict-origin-when-cross-origin";
    private String permissionsPolicy = "camera=(), microphone=(), geolocation=()";
    private String contentSecurityPolicy;
    private long hstsSeconds = 31_536_000;
    private boolean hsts = true;

    private SecurityHeaders() {
    }

    public static SecurityHeaders standard() {
        return new SecurityHeaders();
    }

    public SecurityHeaders frameOptions(String value) {
        frameOptions = value;
        return this;
    }

    public SecurityHeaders referrerPolicy(String value) {
        referrerPolicy = value;
        return this;
    }

    public SecurityHeaders permissionsPolicy(String value) {
        permissionsPolicy = value;
        return this;
    }

    public SecurityHeaders csp(String value) {
        contentSecurityPolicy = value;
        return this;
    }

    public SecurityHeaders hsts(boolean value) {
        hsts = value;
        return this;
    }

    public SecurityHeaders hstsSeconds(long value) {
        hstsSeconds = value;
        return this;
    }

    @Override
    public Object handle(Context context, Chain chain) throws Exception {
        var response = context.response();
        response.header("X-Content-Type-Options", "nosniff");
        if (frameOptions != null) {
            response.header("X-Frame-Options", frameOptions);
        }
        if (referrerPolicy != null) {
            response.header("Referrer-Policy", referrerPolicy);
        }
        if (permissionsPolicy != null) {
            response.header("Permissions-Policy", permissionsPolicy);
        }
        if (contentSecurityPolicy != null) {
            response.header("Content-Security-Policy", contentSecurityPolicy);
        }
        if (hsts && context.request().secure()) {
            response.header("Strict-Transport-Security",
                    "max-age=" + hstsSeconds + "; includeSubDomains");
        }
        return chain.proceed(context);
    }
}
