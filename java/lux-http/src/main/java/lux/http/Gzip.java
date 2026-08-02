package lux.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPOutputStream;

final class Gzip {

    private Gzip() {
    }

    static boolean worthwhile(IncomingRequest request, Headers headers, ServerOptions options, int length) {
        if (request == null || options.gzipMinBytes() <= 0 || length < options.gzipMinBytes()) {
            return false;
        }
        if (headers.has("Content-Encoding")) {
            return false;
        }
        if (!request.headers().contains("Accept-Encoding", "gzip")) {
            return false;
        }
        return compressible(headers.get("Content-Type"));
    }

    static byte[] compress(byte[] data) {
        ByteArrayOutputStream collected = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        try (GZIPOutputStream out = new GZIPOutputStream(collected, 8_192)) {
            out.write(data);
        } catch (IOException cause) {
            throw new UncheckedIOException("no se pudo comprimir la respuesta", cause);
        }
        return collected.toByteArray();
    }

    private static boolean compressible(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase();
        return type.startsWith("text/")
                || type.startsWith("application/json")
                || type.startsWith("application/javascript")
                || type.startsWith("application/xml")
                || type.startsWith("application/xhtml")
                || type.startsWith("image/svg+xml");
    }
}
