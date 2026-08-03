package lux.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StaticFiles {

    private static final int STREAM_THRESHOLD = 1 << 20;

    private final Path root;
    private final String resourcePrefix;
    private final String mount;
    private final String indexFile;
    private final Map<String, byte[]> classpathCache = new ConcurrentHashMap<>();

    private StaticFiles(Path root, String resourcePrefix, String mount, String indexFile) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.resourcePrefix = resourcePrefix;
        this.mount = mount;
        this.indexFile = indexFile;
    }

    public static Handler from(Path root) {
        return from(root, "/", "index.html");
    }

    public static Handler from(Path root, String mount) {
        return from(root, mount, "index.html");
    }

    public static Handler from(Path root, String mount, String indexFile) {
        StaticFiles files = new StaticFiles(root, null, mount, indexFile);
        return files::serve;
    }

    public static Handler fromClasspath(String prefix) {
        return fromClasspath(prefix, "/", "index.html");
    }

    public static Handler fromClasspath(String prefix, String mount) {
        return fromClasspath(prefix, mount, "index.html");
    }

    public static Handler fromClasspath(String prefix, String mount, String indexFile) {
        String head = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
        StaticFiles files = new StaticFiles(null, head, mount, indexFile);
        return files::serve;
    }

    private void serve(Request request, Response response) throws IOException {
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            response.status(405).header("Allow", "GET, HEAD").text("método no permitido");
            return;
        }
        String relative = relativePath(request.path());
        if (relative == null) {
            response.status(404).text("no encontrado");
            return;
        }
        if (root != null) {
            serveFromDisk(request, response, relative);
        } else {
            serveFromClasspath(request, response, relative);
        }
    }

    private void serveFromDisk(Request request, Response response, String relative) throws IOException {
        Path target = resolve(relative);
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
        if (notModified(request, response, etag)) {
            return;
        }

        response.header("ETag", etag);
        response.header("Last-Modified", HttpDate.format(modified));
        response.header("Accept-Ranges", "bytes");
        response.type(MimeTypes.of(target.getFileName().toString()));

        long tamano = attributes.size();
        Ranges pedido = rangoPedido(request, etag, tamano);
        if (pedido == Ranges.NO_SATISFACIBLE) {
            response.status(416).header("Content-Range", "bytes */" + tamano).send(new byte[0]);
            return;
        }
        if (pedido != null) {
            response.status(206).header("Content-Range", pedido.contentRange(tamano));
            response.send(leerTrozo(target, pedido));
            return;
        }

        if (tamano > STREAM_THRESHOLD) {
            try (OutputStream out = response.stream()) {
                Files.copy(target, out);
            }
        } else {
            response.send(Files.readAllBytes(target));
        }
    }

    /** El intervalo pedido, o {@code null} si hay que servir el recurso entero. */
    private static Ranges rangoPedido(Request request, String etag, long tamano) {
        String condicion = request.header("If-Range");
        if (condicion != null && !condicion.trim().equals(etag)) {
            return null;
        }
        return Ranges.parse(request.header("Range"), tamano);
    }

    private static byte[] leerTrozo(Path archivo, Ranges rango) throws IOException {
        long longitud = rango.longitud();
        if (longitud > STREAM_THRESHOLD) {
            longitud = STREAM_THRESHOLD;
        }
        byte[] trozo = new byte[(int) longitud];
        try (java.nio.channels.SeekableByteChannel canal = Files.newByteChannel(archivo)) {
            canal.position(rango.desde());
            java.nio.ByteBuffer destino = java.nio.ByteBuffer.wrap(trozo);
            while (destino.hasRemaining() && canal.read(destino) > 0) {
                continue;
            }
            return destino.hasRemaining()
                    ? java.util.Arrays.copyOf(trozo, destino.position())
                    : trozo;
        }
    }

    private void serveFromClasspath(Request request, Response response, String relative) {
        String name = relative.isEmpty() ? indexFile : relative;
        byte[] content = classpathCache.computeIfAbsent(name, this::readResource);
        if (content.length == 0 && !classpathExists(name)) {
            String withIndex = name.endsWith("/") ? name + indexFile : name + "/" + indexFile;
            content = classpathCache.computeIfAbsent(withIndex, this::readResource);
            if (!classpathExists(withIndex)) {
                response.status(404).text("no encontrado");
                return;
            }
            name = withIndex;
        }

        String etag = "\"" + Integer.toHexString(java.util.Arrays.hashCode(content)) + "\"";
        if (notModified(request, response, etag)) {
            return;
        }
        response.header("ETag", etag);
        response.header("Accept-Ranges", "bytes");
        response.type(MimeTypes.of(name));

        Ranges pedido = rangoPedido(request, etag, content.length);
        if (pedido == Ranges.NO_SATISFACIBLE) {
            response.status(416).header("Content-Range", "bytes */" + content.length).send(new byte[0]);
            return;
        }
        if (pedido != null) {
            response.status(206).header("Content-Range", pedido.contentRange(content.length));
            response.send(java.util.Arrays.copyOfRange(content,
                    (int) pedido.desde(), (int) pedido.hasta() + 1));
            return;
        }
        response.send(content);
    }

    private boolean notModified(Request request, Response response, String etag) {
        if (!etag.equals(request.header("If-None-Match"))) {
            return false;
        }
        response.status(304).header("ETag", etag).send(new byte[0]);
        return true;
    }

    private byte[] readResource(String name) {
        try (InputStream source = loader().getResourceAsStream(resourcePrefix + name)) {
            return source == null ? new byte[0] : source.readAllBytes();
        } catch (IOException cause) {
            return new byte[0];
        }
    }

    private boolean classpathExists(String name) {
        return loader().getResource(resourcePrefix + name) != null;
    }

    private static ClassLoader loader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context == null ? StaticFiles.class.getClassLoader() : context;
    }

    private String relativePath(String requestPath) {
        String relative = requestPath;
        if (!mount.equals("/")) {
            if (!relative.startsWith(mount)) {
                return null;
            }
            relative = relative.substring(mount.length());
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.indexOf('\0') >= 0 || relative.contains("..")) {
            return null;
        }
        return relative;
    }

    private Path resolve(String relative) {
        if (relative.isEmpty()) {
            return root;
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
