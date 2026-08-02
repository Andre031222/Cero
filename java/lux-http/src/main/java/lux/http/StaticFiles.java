package lux.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public final class StaticFiles {

    private static final int STREAM_THRESHOLD = 1 << 20;

    private final Path root;
    private final String prefix;
    private final String indexFile;

    private StaticFiles(Path root, String prefix, String indexFile) {
        this.root = root.toAbsolutePath().normalize();
        this.prefix = prefix;
        this.indexFile = indexFile;
    }

    public static Handler from(Path root) {
        return from(root, "/", "index.html");
    }

    public static Handler from(Path root, String prefix) {
        return from(root, prefix, "index.html");
    }

    public static Handler from(Path root, String prefix, String indexFile) {
        StaticFiles files = new StaticFiles(root, prefix, indexFile);
        return files::serve;
    }

    private void serve(Request request, Response response) throws IOException {
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            response.status(405).header("Allow", "GET, HEAD").text("método no permitido");
            return;
        }

        Path target = resolve(request.path());
        if (target == null || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            response.status(404).text("no encontrado");
            return;
        }

        BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);
        if (attributes.isDirectory()) {
            target = target.resolve(indexFile);
            if (!Files.isRegularFile(target)) {
                response.status(404).text("no encontrado");
                return;
            }
            attributes = Files.readAttributes(target, BasicFileAttributes.class);
        }
        if (!attributes.isRegularFile()) {
            response.status(404).text("no encontrado");
            return;
        }

        long modified = attributes.lastModifiedTime().toMillis();
        String etag = "\"" + Long.toHexString(attributes.size()) + "-" + Long.toHexString(modified) + "\"";

        if (etag.equals(request.header("If-None-Match"))) {
            response.status(304).header("ETag", etag).send(new byte[0]);
            return;
        }

        response.header("ETag", etag);
        response.header("Last-Modified", HttpDate.format(modified));
        response.type(MimeTypes.of(target.getFileName().toString()));

        if (attributes.size() > STREAM_THRESHOLD) {
            try (OutputStream out = response.stream()) {
                Files.copy(target, out);
            }
        } else {
            response.send(Files.readAllBytes(target));
        }
    }

    private Path resolve(String requestPath) {
        String relative = requestPath;
        if (!prefix.equals("/")) {
            if (!relative.startsWith(prefix)) {
                return null;
            }
            relative = relative.substring(prefix.length());
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isEmpty()) {
            return root;
        }
        if (relative.indexOf('\0') >= 0) {
            return null;
        }

        Path candidate;
        try {
            candidate = root.resolve(relative).normalize();
        } catch (RuntimeException invalid) {
            return null;
        }
        return candidate.startsWith(root) ? candidate : null;
    }
}
