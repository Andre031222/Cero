package corvo.core;

import corvo.http.HttpException;
import corvo.http.HttpMethod;
import corvo.http.Session;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class Csrf implements Middleware {

    public static final String HEADER = "X-CSRF-Token";
    public static final String FIELD = "_csrf";

    private static final String SESSION_KEY = "corvo.csrf";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final List<String> exempt = new ArrayList<>();

    private Csrf() {
    }

    public static Csrf enabled() {
        return new Csrf();
    }

    public Csrf exempt(String... pathPrefixes) {
        exempt.addAll(List.of(pathPrefixes));
        return this;
    }

    public static String token(Context context) {
        Session session = context.session();
        Object existing = session.get(SESSION_KEY);
        if (existing instanceof String token && !token.isEmpty()) {
            return token;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.set(SESSION_KEY, token);
        return token;
    }

    @Override
    public Object handle(Context context, Chain chain) throws Exception {
        if (safe(context.method()) || exempted(context.path())) {
            return chain.proceed(context);
        }
        if (context.routed() && context.route().action() != null
                && context.route().action().isAnnotationPresent(CsrfExempt.class)) {
            return chain.proceed(context);
        }

        Session session = context.session(false);
        Object expected = session == null ? null : session.get(SESSION_KEY);
        if (!(expected instanceof String token) || token.isEmpty()) {
            throw new HttpException(403, "no hay token CSRF en la sesión");
        }

        String presented = context.header(HEADER);
        if (presented == null) {
            presented = context.form(FIELD);
        }
        if (presented == null) {
            presented = context.query(FIELD);
        }
        if (presented == null || !constantTimeEquals(token, presented)) {
            throw new HttpException(403, "token CSRF inválido o ausente");
        }
        return chain.proceed(context);
    }

    private boolean exempted(String path) {
        for (String prefix : exempt) {
            // Con startsWith pelado, eximir /api/publico eximía también /api/publicoSECRETO.
            // Una exención de seguridad no es sitio para sorpresas: o es igual, o el corte cae
            // en una barra.
            if (!path.startsWith(prefix)) {
                continue;
            }
            if (path.length() == prefix.length()
                    || prefix.endsWith("/")
                    || path.charAt(prefix.length()) == '/') {
                return true;
            }
        }
        return false;
    }

    private static boolean safe(HttpMethod method) {
        return method == HttpMethod.GET || method == HttpMethod.HEAD
                || method == HttpMethod.OPTIONS || method == HttpMethod.TRACE;
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = presented.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int difference = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            difference |= a[i] ^ b[i];
        }
        return difference == 0;
    }
}
