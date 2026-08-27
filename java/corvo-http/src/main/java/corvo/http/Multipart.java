package corvo.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Multipart {

    private Multipart() {
    }

    static boolean applies(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("multipart/form-data");
    }

    static List<Part> parse(byte[] body, String contentType, int maxParts) {
        byte[] delimiter = ("--" + boundary(contentType)).getBytes(StandardCharsets.ISO_8859_1);
        List<Part> parts = new ArrayList<>(4);

        int cursor = indexOf(body, delimiter, 0);
        if (cursor < 0) {
            throw new HttpException(400, "multipart sin delimitador inicial");
        }
        cursor += delimiter.length;

        while (true) {
            if (cursor + 1 < body.length && body[cursor] == '-' && body[cursor + 1] == '-') {
                return parts;
            }
            cursor = skipLineBreak(body, cursor);

            int headerEnd = indexOf(body, new byte[]{'\r', '\n', '\r', '\n'}, cursor);
            if (headerEnd < 0) {
                throw new HttpException(400, "parte multipart sin cabeceras");
            }
            String rawHeaders = new String(body, cursor, headerEnd - cursor, StandardCharsets.ISO_8859_1);
            int contentStart = headerEnd + 4;

            int next = indexOf(body, delimiter, contentStart);
            if (next < 0) {
                throw new HttpException(400, "parte multipart sin delimitador de cierre");
            }
            int contentEnd = next;
            if (contentEnd >= 2 && body[contentEnd - 2] == '\r' && body[contentEnd - 1] == '\n') {
                contentEnd -= 2;
            }

            if (parts.size() >= maxParts) {
                throw new HttpException(413, "demasiadas partes en el multipart");
            }
            parts.add(build(rawHeaders, Arrays.copyOfRange(body, contentStart, contentEnd)));
            cursor = next + delimiter.length;
        }
    }

    private static Part build(String rawHeaders, byte[] content) {
        String name = null;
        String filename = null;
        String contentType = null;

        for (String line : rawHeaders.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String header = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();

            if (header.equalsIgnoreCase("Content-Disposition")) {
                name = attribute(value, "name");
                filename = attribute(value, "filename");
            } else if (header.equalsIgnoreCase("Content-Type")) {
                contentType = value;
            }
        }
        if (name == null) {
            throw new HttpException(400, "parte multipart sin nombre");
        }
        return new Part(name, filename, contentType, content);
    }

    private static String attribute(String disposition, String key) {
        String needle = key + "=\"";
        int start = disposition.toLowerCase().indexOf(needle.toLowerCase());
        if (start < 0) {
            return null;
        }
        start += needle.length();
        int end = disposition.indexOf('"', start);
        return end < 0 ? null : disposition.substring(start, end);
    }

    private static String boundary(String contentType) {
        int mark = contentType.toLowerCase().indexOf("boundary=");
        if (mark < 0) {
            throw new HttpException(400, "multipart sin boundary");
        }
        String value = contentType.substring(mark + 9).trim();
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).trim();
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new HttpException(400, "boundary vacío");
        }
        return value;
    }

    private static int skipLineBreak(byte[] body, int cursor) {
        if (cursor + 1 < body.length && body[cursor] == '\r' && body[cursor + 1] == '\n') {
            return cursor + 2;
        }
        return cursor;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
