package corvo.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Parser {

    private final String name;
    private final String source;
    private final Map<String, List<Node>> blocks = new LinkedHashMap<>();

    private int at;
    private String parent;

    private Parser(String name, String source) {
        this.name = name;
        this.source = source;
    }

    static Template parse(String name, String source) {
        Parser parser = new Parser(name, source);
        Section body = parser.until(Set.of());
        if (body.stop() != null) {
            throw TemplateException.at(name, parser.line(), "etiqueta '" + body.stop() + "' sin apertura");
        }
        return new Template(name, body.nodes(), parser.blocks, parser.parent);
    }

    private Section until(Set<String> stops) {
        List<Node> nodes = new ArrayList<>();
        StringBuilder literal = new StringBuilder();

        while (at < source.length()) {
            int open = nextTag();
            if (open < 0) {
                literal.append(source, at, source.length());
                at = source.length();
                break;
            }
            literal.append(source, at, open);
            char kind = source.charAt(open + 1);
            at = open + 2;

            if (kind == '#') {
                skipTo("#}");
                continue;
            }
            if (kind == '{') {
                flush(literal, nodes);
                nodes.add(interpolation(readTo("}}")));
                continue;
            }

            String tag = readTo("%}").trim();
            String head = tag.isEmpty() ? "" : tag.split("\\s+", 2)[0];
            String rest = tag.length() > head.length() ? tag.substring(head.length()).trim() : "";

            if (stops.contains(head)) {
                flush(literal, nodes);
                return new Section(nodes, head, rest);
            }

            flush(literal, nodes);
            switch (head) {
                case "if" -> nodes.add(conditional(rest));
                case "for" -> nodes.add(loop(rest));
                case "include" -> nodes.add(new Nodes.Include(unquote(rest)));
                case "extends" -> parent = unquote(rest);
                case "block" -> nodes.add(block(rest));
                default -> throw TemplateException.at(name, line(), "etiqueta desconocida: '" + head + "'");
            }
        }
        flush(literal, nodes);
        return new Section(nodes, null, null);
    }

    private Node interpolation(String body) {
        String trimmed = body.trim();
        boolean escaped = !trimmed.startsWith("&");
        String expression = escaped ? trimmed : trimmed.substring(1).trim();
        if (expression.isEmpty()) {
            throw TemplateException.at(name, line(), "interpolación vacía");
        }
        return new Nodes.Value(compile(expression), escaped);
    }

    private Node conditional(String condition) {
        List<Nodes.Branch> branches = new ArrayList<>();
        List<Node> otherwise = List.of();
        String pending = condition;

        while (true) {
            Section section = until(Set.of("elseif", "else", "end"));
            branches.add(new Nodes.Branch(compile(pending), section.nodes()));

            if (section.stop() == null) {
                throw TemplateException.at(name, line(), "falta {% end %} para {% if %}");
            }
            if (section.stop().equals("elseif")) {
                pending = section.argument();
                continue;
            }
            if (section.stop().equals("else")) {
                Section tail = until(Set.of("end"));
                if (tail.stop() == null) {
                    throw TemplateException.at(name, line(), "falta {% end %} para {% else %}");
                }
                otherwise = tail.nodes();
            }
            return new Nodes.If(branches, otherwise);
        }
    }

    private Node loop(String header) {
        String[] parts = header.split("\\s+in\\s+", 2);
        if (parts.length != 2 || parts[0].isBlank()) {
            throw TemplateException.at(name, line(), "sintaxis esperada: {% for elemento in lista %}");
        }
        Section body = until(Set.of("else", "end"));
        List<Node> empty = List.of();

        if (body.stop() == null) {
            throw TemplateException.at(name, line(), "falta {% end %} para {% for %}");
        }
        if (body.stop().equals("else")) {
            Section tail = until(Set.of("end"));
            if (tail.stop() == null) {
                throw TemplateException.at(name, line(), "falta {% end %} para {% else %}");
            }
            empty = tail.nodes();
        }
        return new Nodes.For(parts[0].trim(), compile(parts[1].trim()), body.nodes(), empty);
    }

    private Node block(String header) {
        String blockName = unquote(header);
        if (blockName.isEmpty()) {
            throw TemplateException.at(name, line(), "el bloque necesita un nombre");
        }
        Section body = until(Set.of("end"));
        if (body.stop() == null) {
            throw TemplateException.at(name, line(), "falta {% end %} para {% block %}");
        }
        blocks.put(blockName, body.nodes());
        return new Nodes.Block(blockName, body.nodes());
    }

    private Expression compile(String expression) {
        try {
            return Expression.compile(expression);
        } catch (TemplateException cause) {
            throw TemplateException.at(name, line(), cause.getMessage());
        }
    }

    private int nextTag() {
        for (int i = at; i < source.length() - 1; i++) {
            if (source.charAt(i) != '{') {
                continue;
            }
            char next = source.charAt(i + 1);
            if (next == '{' || next == '%' || next == '#') {
                return i;
            }
        }
        return -1;
    }

    private String readTo(String close) {
        int end = source.indexOf(close, at);
        if (end < 0) {
            throw TemplateException.at(name, line(), "falta '" + close + "'");
        }
        String body = source.substring(at, end);
        at = end + close.length();
        return body;
    }

    private void skipTo(String close) {
        int end = source.indexOf(close, at);
        at = end < 0 ? source.length() : end + close.length();
    }

    private void flush(StringBuilder literal, List<Node> nodes) {
        if (literal.length() > 0) {
            nodes.add(new Nodes.Text(literal.toString()));
            literal.setLength(0);
        }
    }

    private int line() {
        int line = 1;
        for (int i = 0; i < Math.min(at, source.length()); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String unquote(String value) {
        String text = value.trim();
        if (text.length() > 1 && (text.startsWith("\"") && text.endsWith("\"")
                || text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private record Section(List<Node> nodes, String stop, String argument) {
    }
}
