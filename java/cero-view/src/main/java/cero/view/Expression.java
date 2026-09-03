package cero.view;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class Expression {

    private final Term root;
    private final String source;

    private Expression(Term root, String source) {
        this.root = root;
        this.source = source;
    }

    public static Expression compile(String source) {
        Reader reader = new Reader(source);
        Term term = reader.or();
        reader.expectEnd();
        return new Expression(term, source);
    }

    public Object evaluate(Scope scope) {
        return root.value(scope);
    }

    public boolean truthy(Scope scope) {
        return truthy(root.value(scope));
    }

    public String source() {
        return source;
    }

    public static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean flag -> flag;
            case CharSequence text -> !text.isEmpty();
            case Number number -> number.doubleValue() != 0;
            case Collection<?> items -> !items.isEmpty();
            case Map<?, ?> map -> !map.isEmpty();
            case Object any when any.getClass().isArray() -> ((Object[]) any).length > 0;
            default -> true;
        };
    }

    private interface Term {
        Object value(Scope scope);
    }

    private record Literal(Object constant) implements Term {
        @Override
        public Object value(Scope scope) {
            return constant;
        }
    }

    private record Lookup(List<String> path) implements Term {
        @Override
        public Object value(Scope scope) {
            Object current = scope.lookup(path.get(0));
            for (int i = 1; i < path.size() && current != null; i++) {
                current = Scope.property(current, path.get(i));
            }
            return current;
        }
    }

    private record Not(Term inner) implements Term {
        @Override
        public Object value(Scope scope) {
            return !truthy(inner.value(scope));
        }
    }

    private record Binary(String operator, Term left, Term right) implements Term {
        @Override
        public Object value(Scope scope) {
            if (operator.equals("and")) {
                return truthy(left.value(scope)) && truthy(right.value(scope));
            }
            if (operator.equals("or")) {
                return truthy(left.value(scope)) || truthy(right.value(scope));
            }
            Object a = left.value(scope);
            Object b = right.value(scope);
            return switch (operator) {
                case "==" -> equal(a, b);
                case "!=" -> !equal(a, b);
                default -> compare(a, b, operator);
            };
        }

        private static boolean equal(Object a, Object b) {
            if (a instanceof Number left && b instanceof Number right) {
                return left.doubleValue() == right.doubleValue();
            }
            return a == null ? b == null : a.equals(b);
        }

        private static boolean compare(Object a, Object b, String operator) {
            if (!(a instanceof Number left) || !(b instanceof Number right)) {
                throw new TemplateException("no se puede comparar " + a + " " + operator + " " + b);
            }
            int sign = Double.compare(left.doubleValue(), right.doubleValue());
            return switch (operator) {
                case "<" -> sign < 0;
                case ">" -> sign > 0;
                case "<=" -> sign <= 0;
                case ">=" -> sign >= 0;
                default -> throw new TemplateException("operador desconocido: " + operator);
            };
        }
    }

    private static final class Reader {

        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        Term or() {
            Term left = and();
            while (word("or")) {
                left = new Binary("or", left, and());
            }
            return left;
        }

        private Term and() {
            Term left = equality();
            while (word("and")) {
                left = new Binary("and", left, equality());
            }
            return left;
        }

        private Term equality() {
            Term left = comparison();
            while (true) {
                if (symbol("==")) {
                    left = new Binary("==", left, comparison());
                } else if (symbol("!=")) {
                    left = new Binary("!=", left, comparison());
                } else {
                    return left;
                }
            }
        }

        private Term comparison() {
            Term left = unary();
            while (true) {
                if (symbol("<=")) {
                    left = new Binary("<=", left, unary());
                } else if (symbol(">=")) {
                    left = new Binary(">=", left, unary());
                } else if (symbol("<")) {
                    left = new Binary("<", left, unary());
                } else if (symbol(">")) {
                    left = new Binary(">", left, unary());
                } else {
                    return left;
                }
            }
        }

        private Term unary() {
            if (symbol("!")) {
                return new Not(unary());
            }
            return primary();
        }

        private Term primary() {
            skip();
            if (at >= text.length()) {
                throw new TemplateException("expresión incompleta: " + text);
            }
            char c = text.charAt(at);
            if (c == '(') {
                at++;
                Term inner = or();
                skip();
                if (at >= text.length() || text.charAt(at) != ')') {
                    throw new TemplateException("falta ')' en: " + text);
                }
                at++;
                return inner;
            }
            if (c == '"' || c == '\'') {
                return new Literal(quoted(c));
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return new Literal(number());
            }
            List<String> path = path();
            if (path.size() == 1) {
                switch (path.get(0)) {
                    case "true" -> {
                        return new Literal(Boolean.TRUE);
                    }
                    case "false" -> {
                        return new Literal(Boolean.FALSE);
                    }
                    case "null" -> {
                        return new Literal(null);
                    }
                    default -> {
                    }
                }
            }
            return new Lookup(path);
        }

        private String quoted(char delimiter) {
            at++;
            StringBuilder value = new StringBuilder();
            while (at < text.length() && text.charAt(at) != delimiter) {
                value.append(text.charAt(at++));
            }
            if (at >= text.length()) {
                throw new TemplateException("cadena sin cerrar en: " + text);
            }
            at++;
            return value.toString();
        }

        private Number number() {
            int start = at;
            if (text.charAt(at) == '-') {
                at++;
            }
            boolean decimal = false;
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                decimal = decimal || text.charAt(at) == '.';
                at++;
            }
            String raw = text.substring(start, at);
            return decimal ? Double.valueOf(raw) : Long.valueOf(raw);
        }

        private List<String> path() {
            List<String> parts = new ArrayList<>(2);
            StringBuilder part = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    part.append(c);
                    at++;
                } else if (c == '.' && part.length() > 0) {
                    parts.add(part.toString());
                    part.setLength(0);
                    at++;
                } else {
                    break;
                }
            }
            if (part.length() > 0) {
                parts.add(part.toString());
            }
            if (parts.isEmpty()) {
                throw new TemplateException("expresión inválida: " + text);
            }
            return parts;
        }

        private boolean symbol(String token) {
            skip();
            if (text.startsWith(token, at)) {
                at += token.length();
                return true;
            }
            return false;
        }

        private boolean word(String token) {
            skip();
            if (!text.startsWith(token, at)) {
                return false;
            }
            int after = at + token.length();
            if (after < text.length() && (Character.isLetterOrDigit(text.charAt(after)) || text.charAt(after) == '_')) {
                return false;
            }
            at = after;
            return true;
        }

        private void skip() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        void expectEnd() {
            skip();
            if (at < text.length()) {
                throw new TemplateException("sobra '" + text.substring(at) + "' en: " + text);
            }
        }
    }
}
