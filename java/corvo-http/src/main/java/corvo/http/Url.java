package corvo.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Url {

    private Url() {
    }

    public static String decodePath(String path) {
        if (path.indexOf('%') < 0) {
            return path;
        }
        return decode(path, false);
    }

    public static Map<String, List<String>> parseQuery(String raw) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        int from = 0;
        while (from <= raw.length()) {
            int amp = raw.indexOf('&', from);
            int end = amp < 0 ? raw.length() : amp;
            if (end > from) {
                int equals = raw.indexOf('=', from);
                String name;
                String value;
                if (equals < 0 || equals > end) {
                    name = raw.substring(from, end);
                    value = "";
                } else {
                    name = raw.substring(from, equals);
                    value = raw.substring(equals + 1, end);
                }
                params.computeIfAbsent(decodeForm(name), key -> new ArrayList<>(1)).add(decodeForm(value));
            }
            if (amp < 0) {
                break;
            }
            from = amp + 1;
        }
        return params;
    }

    private static String decodeForm(String text) {
        if (text.indexOf('%') < 0 && text.indexOf('+') < 0) {
            return text;
        }
        return decode(text, true);
    }

    private static String decode(String text, boolean plusIsSpace) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length()) {
                int high = hex(text.charAt(i + 1));
                int low = hex(text.charAt(i + 2));
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) | low);
                    i += 2;
                    continue;
                }
                throw new HttpException(400, "codificación porcentual inválida");
            }
            if (c == '+' && plusIsSpace) {
                out.write(' ');
            } else if (c < 0x80) {
                out.write(c);
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(encoded, 0, encoded.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
