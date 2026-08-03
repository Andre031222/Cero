package lux.core;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

final class RoutePattern implements Comparable<RoutePattern> {

    static final String WILDCARD = "*";

    private final String raw;
    private final String[] segments;
    private final boolean trailingWildcard;

    /** Nombre de la variable de cada segmento, o {@code null} si el segmento es literal. */
    private final String[] variableNames;
    private final boolean hasVariables;

    private RoutePattern(String raw, String[] segments, boolean trailingWildcard) {
        this.raw = raw;
        this.segments = segments;
        this.trailingWildcard = trailingWildcard;
        this.variableNames = new String[segments.length];
        boolean any = false;
        for (int i = 0; i < segments.length; i++) {
            if (isVariable(segments[i])) {
                variableNames[i] = segments[i].substring(1, segments[i].length() - 1);
                any = true;
            }
        }
        this.hasVariables = any;
    }

    static RoutePattern of(String pattern) {
        String normalized = normalize(pattern);
        String[] parts = split(normalized);
        boolean wildcard = parts.length > 0 && parts[parts.length - 1].equals(WILDCARD);
        if (wildcard) {
            parts = Arrays.copyOf(parts, parts.length - 1);
        }
        for (String part : parts) {
            if (part.equals(WILDCARD)) {
                throw new IllegalArgumentException("el comodín solo puede ir al final: " + pattern);
            }
            if (part.startsWith("{") && !part.endsWith("}")) {
                throw new IllegalArgumentException("variable de ruta mal formada: " + part);
            }
        }
        return new RoutePattern(normalized, parts, wildcard);
    }

    /** Comodidad para quien tenga el camino sin partir. */
    Map<String, String> match(String path) {
        String[] parts = parts(path);
        return matches(parts) ? variables(parts) : null;
    }

    static String[] parts(String path) {
        return split(normalize(path));
    }

    /** No asigna nada. */
    boolean matches(String[] parts) {
        if (trailingWildcard ? parts.length < segments.length : parts.length != segments.length) {
            return false;
        }
        for (int i = 0; i < segments.length; i++) {
            if (variableNames[i] == null && !segments[i].equals(parts[i])) {
                return false;
            }
        }
        return true;
    }

    /** Solo se llama sobre la ruta que ganó. Sin variables devuelve el mapa vacío compartido. */
    Map<String, String> variables(String[] parts) {
        if (!hasVariables && !trailingWildcard) {
            return Map.of();
        }
        Map<String, String> variables = new LinkedHashMap<>(4);
        for (int i = 0; i < segments.length; i++) {
            if (variableNames[i] != null) {
                variables.put(variableNames[i], parts[i]);
            }
        }
        if (trailingWildcard) {
            StringBuilder resto = new StringBuilder();
            for (int i = segments.length; i < parts.length; i++) {
                if (i > segments.length) {
                    resto.append('/');
                }
                resto.append(parts[i]);
            }
            variables.put(WILDCARD, resto.toString());
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

    private static final String[] SIN_SEGMENTOS = new String[0];

    private static String[] split(String path) {
        if (path.equals("/")) {
            return SIN_SEGMENTOS;
        }
        return path.substring(1).split("/", -1);
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
