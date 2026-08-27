package corvo.core;

@FunctionalInterface
public interface ViewRenderer {

    String render(String template, Object model) throws Exception;
}
