package lux.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RoutePattern implements Comparable<RoutePattern> {

    static final String WILDCARD = "*";

    private final String raw;
    private final String[] segments;
    private final boolean trailingWildcard;

    private RoutePattern(String raw, String[] segments, boolean trailingWildcard) {
        this.raw = raw;
        this.segments = segments;
        this.trailingWildcard = trailingWildcard;
    }

    static RoutePattern of(String pattern) {
        String normalized = normalize(pattern);
        List<String> parts = split(normalized);
        boolean wildcard = !parts.isEmpty() && parts.get(parts.size() - 1).equals(WILDCARD);
        if (wildcard) {
            parts = parts.subList(0, parts.size() - 1);
        }
        for (String part : parts) {
            if (part.equals(WILDCARD)) {
                throw new IllegalArgumentException("el comodín solo puede ir al final: " + pattern);
            }
            if (part.startsWith("{") && !part.endsWith("}")) {
                throw new IllegalArgumentException("variable de ruta mal formada: " + part);
            }
        }
        return new RoutePattern(normalized, parts.toArray(String[]::new), wildcard);
    }

    Map<String, String> match(String path) {
        List<String> parts = split(normalize(path));
        if (trailingWildcard ? parts.size() < segments.length : parts.size() != segments.length) {
            return null;
        }
        Map<String, String> variables = new LinkedHashMap<>(4);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            String actual = parts.get(i);
            if (isVariable(segment)) {
                variables.put(segment.substring(1, segment.length() - 1), actual);
            } else if (!segment.equals(actual)) {
                return null;
            }
        }
        if (trailingWildcard) {
            variables.put(WILDCARD, String.join("/", parts.subList(segments.length, parts.size())));
        }
        return variables;
    }

    String raw() {
        return raw;
    }

    @Override
    public int compareTo(RoutePattern other) {
        int shared = Math.min(segments.length, other.segments.length);
        for (int i = 0; i < shared; i++) {
            int weight = Integer.compare(weight(other.segments[i]), weight(segments[i]));
            if (weight != 0) {
                return weight;
            }
        }
        int length = Integer.compare(other.segments.length, segments.length);
        if (length != 0) {
            return length;
        }
        int wildcard = Boolean.compare(trailingWildcard, other.trailingWildcard);
        return wildcard != 0 ? wildcard : raw.compareTo(other.raw);
    }

    @Override
    public String toString() {
        return raw;
    }

    private static int weight(String segment) {
        return isVariable(segment) ? 1 : 2;
    }

    private static boolean isVariable(String segment) {
        return segment.length() > 1 && segment.charAt(0) == '{' && segment.endsWith("}");
    }

    private static List<String> split(String path) {
        if (path.equals("/")) {
            return List.of();
        }
        return List.of(path.substring(1).split("/", -1));
    }

    private static String normalize(String path) {
        String value = path == null || path.isBlank() ? "/" : path.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
