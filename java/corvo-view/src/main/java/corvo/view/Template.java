package corvo.view;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Template {

    private final String name;
    private final List<Node> body;
    private final Map<String, List<Node>> blocks;
    private final String parent;

    Template(String name, List<Node> body, Map<String, List<Node>> blocks, String parent) {
        this.name = name;
        this.body = List.copyOf(body);
        this.blocks = Map.copyOf(blocks);
        this.parent = parent;
    }

    public static Template compile(String name, String source) {
        return Parser.parse(name, source);
    }

    public String name() {
        return name;
    }

    public String parent() {
        return parent;
    }

    List<Node> body() {
        return body;
    }

    String render(Object model, Templates templates) {
        return render(model, templates, java.util.Map.of());
    }

    String render(Object model, Templates templates, Map<String, Object> globals) {
        Map<String, List<Node>> inherited = new HashMap<>(blocks);
        Set<String> visited = new LinkedHashSet<>();
        visited.add(name);

        Template root = this;
        while (root.parent != null) {
            if (!visited.add(root.parent)) {
                throw new TemplateException("herencia circular de plantillas: " + visited + " → " + root.parent);
            }
            root = templates.load(root.parent);
            root.blocks.forEach(inherited::putIfAbsent);
        }

        StringBuilder out = new StringBuilder(512);
        Nodes.renderAll(root.body, out, new Scope(model, globals), new Node.Render(templates, inherited));
        return out.toString();
    }
}
