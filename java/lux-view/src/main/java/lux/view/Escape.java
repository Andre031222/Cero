package lux.view;

public final class Escape {

    private Escape() {
    }

    public static String html(String text) {
        if (text == null) {
            return "";
        }
        int first = indexOfSpecial(text);
        if (first < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        out.append(text, 0, first);
        for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static int indexOfSpecial(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') {
                return i;
            }
        }
        return -1;
    }
}
