package lux.http;

import java.nio.charset.StandardCharsets;

public record Part(String name, String filename, String contentType, byte[] bytes) {

    public boolean isFile() {
        return filename != null;
    }

    public int size() {
        return bytes.length;
    }

    public String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
