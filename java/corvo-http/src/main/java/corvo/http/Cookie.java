package corvo.http;

public record Cookie(
        String name,
        String value,
        String path,
        String domain,
        long maxAgeSeconds,
        boolean secure,
        boolean httpOnly,
        String sameSite) {

    private static final long NO_MAX_AGE = Long.MIN_VALUE;

    public Cookie {
        if (!isToken(name)) {
            throw new IllegalArgumentException("nombre de cookie inválido: " + name);
        }
        if (!isValidValue(value)) {
            throw new IllegalArgumentException("valor de cookie inválido para " + name);
        }
    }

    public static Cookie of(String name, String value) {
        return new Cookie(name, value, "/", null, NO_MAX_AGE, false, true, "Lax");
    }

    public static Cookie expired(String name) {
        return of(name, "").maxAge(0);
    }

    public Cookie value(String newValue) {
        return new Cookie(name, newValue, path, domain, maxAgeSeconds, secure, httpOnly, sameSite);
    }

    public Cookie path(String newPath) {
        return new Cookie(name, value, newPath, domain, maxAgeSeconds, secure, httpOnly, sameSite);
    }

    public Cookie domain(String newDomain) {
        return new Cookie(name, value, path, newDomain, maxAgeSeconds, secure, httpOnly, sameSite);
    }

    public Cookie maxAge(long seconds) {
        return new Cookie(name, value, path, domain, seconds, secure, httpOnly, sameSite);
    }

    public Cookie secure(boolean flag) {
        return new Cookie(name, value, path, domain, maxAgeSeconds, flag, httpOnly, sameSite);
    }

    public Cookie httpOnly(boolean flag) {
        return new Cookie(name, value, path, domain, maxAgeSeconds, secure, flag, sameSite);
    }

    public Cookie sameSite(String policy) {
        return new Cookie(name, value, path, domain, maxAgeSeconds, secure, httpOnly, policy);
    }

    public boolean hasMaxAge() {
        return maxAgeSeconds != NO_MAX_AGE;
    }

    String encode() {
        StringBuilder text = new StringBuilder(64);
        text.append(name).append('=').append(value);
        if (path != null) {
            text.append("; Path=").append(path);
        }
        if (domain != null) {
            text.append("; Domain=").append(domain);
        }
        if (hasMaxAge()) {
            text.append("; Max-Age=").append(maxAgeSeconds);
        }
        if (secure) {
            text.append("; Secure");
        }
        if (httpOnly) {
            text.append("; HttpOnly");
        }
        if (sameSite != null) {
            text.append("; SameSite=").append(sameSite);
        }
        return text.toString();
    }

    private static boolean isToken(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '=' || c == ';' || c == ',' || c == '"'
                    || c == '(' || c == ')' || c == '<' || c == '>' || c == '@' || c == ':'
                    || c == '\\' || c == '/' || c == '[' || c == ']' || c == '?' || c == '{' || c == '}') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidValue(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x21 || c > 0x7E || c == ';' || c == ',' || c == '"' || c == '\\') {
                return false;
            }
        }
        return true;
    }
}
