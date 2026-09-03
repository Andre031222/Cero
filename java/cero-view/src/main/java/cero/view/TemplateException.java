package cero.view;

public class TemplateException extends RuntimeException {

    public TemplateException(String message) {
        super(message);
    }

    public TemplateException(String message, Throwable cause) {
        super(message, cause);
    }

    static TemplateException at(String template, int line, String message) {
        return new TemplateException(template + ":" + line + " — " + message);
    }
}
