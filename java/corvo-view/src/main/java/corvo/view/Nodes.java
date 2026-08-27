package corvo.view;

import java.util.Collection;
import java.util.List;
import java.util.Map;

final class Nodes {

    private Nodes() {
    }

    static void renderAll(List<Node> nodes, StringBuilder out, Scope scope, Node.Render render) {
        for (Node node : nodes) {
            node.render(out, scope, render);
        }
    }

    record Text(String literal) implements Node {
        @Override
        public void render(StringBuilder out, Scope scope, Render render) {
            out.append(literal);
        }
    }

    record Value(Expression expression, boolean escaped) implements Node {
        @Override
        public void render(StringBuilder out, Scope scope, Render render) {
            Object value = expression.evaluate(scope);
            if (value == null) {
                return;
            }
            String text = String.valueOf(value);
            out.append(escaped ? Escape.html(text) : text);
        }
    }

    record Branch(Expression condition, List<Node> body) {
    }

    record If(List<Branch> branches, List<Node> otherwise) implements Node {
        @Override
        public void render(StringBuilder out, Scope scope, Render render) {
            for (Branch branch : branches) {
                if (branch.condition().truthy(scope)) {
                    renderAll(branch.body(), out, scope, render);
                    return;
                }
            }
            renderAll(otherwise, out, scope, render);
        }
    }

    record For(String variable, Expression source, List<Node> body, List<Node> empty) implements Node {
        @Override
        public void render(StringBuilder out, Scope scope, Render render) {
            List<?> items = asList(source.evaluate(scope));
            if (items.isEmpty()) {
                renderAll(empty, out, scope, render);
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                Scope inner = scope.child();
                inner.put(variable, items.get(i));
                inner.put("loop", Map.of(
                        "index", i,
                        "number", i + 1,
                        "first", i == 0,
                        "last", i == items.size() - 1,
                        "size", items.size()));
                renderAll(body, out, inner, render);
            }
        }

        private static List<?> asList(Object value) {
            return switch (value) {
                case null -> List.of();
                case List<?> list -> list;
                case Collection<?> items -> List.copyOf(items);
                case Map<?, ?> map -> List.copyOf(map.entrySet());
                case Object any when any.getClass().isArray() -> List.of((Object[]) any);
                default -> List.of(value);
            };
        }
    }

    record Include(String name) implements Node {
        @Override
        public void render(StringBuilder out, Scope scope, Render render) {
            Template included = render.templates().load(name);
            renderAll(included.body(), out, scope, render);
        }
    }

    record Block(String name, List<Node> body) implements Node {
        @Override
        public void render(StringBuilder out, Scope scope, Render render) {
            List<Node> override = render.blocks().get(name);
            renderAll(override != null ? override : body, out, scope, render);
        }
    }
}
