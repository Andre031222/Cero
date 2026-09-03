package cero.view;

import java.util.List;
import java.util.Map;

public interface Node {

    void render(StringBuilder out, Scope scope, Render render);

    record Render(Templates templates, Map<String, List<Node>> blocks) {
    }
}
