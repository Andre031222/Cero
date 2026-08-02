package lux.core;

import java.util.regex.Pattern;

public final class Sanitize {

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style[^>]*>.*?</style>");
    private static final Pattern FRAME = Pattern.compile("(?is)<(iframe|frame|object|embed|applet)[^>]*>.*?</\\1>");
    private static final Pattern VOID_TAGS = Pattern.compile("(?is)<(iframe|frame|object|embed|applet|link|meta)[^>]*/?>");
    private static final Pattern EVENT = Pattern.compile("(?is)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern PROTOCOL = Pattern.compile("(?is)(javascript|vbscript|data)\\s*:");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    private Sanitize() {
    }

    public static String html(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String clean = SCRIPT.matcher(input).replaceAll("");
        clean = STYLE.matcher(clean).replaceAll("");
        clean = FRAME.matcher(clean).replaceAll("");
        clean = VOID_TAGS.matcher(clean).replaceAll("");
        clean = EVENT.matcher(clean).replaceAll("");
        clean = PROTOCOL.matcher(clean).replaceAll("");
        return clean;
    }

    public static String text(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String clean = TAGS.matcher(html(input)).replaceAll(" ");
        return SPACES.matcher(clean).replaceAll(" ").trim();
    }

    public static String filename(String input) {
        if (input == null) {
            return null;
        }
        String name = input.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        return name.isEmpty() ? "archivo" : name;
    }
}
