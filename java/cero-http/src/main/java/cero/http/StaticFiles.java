package cero.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StaticFiles implements Handler {

    private static final int STREAM_THRESHOLD = 1 << 20;

    private final Path root;
    private final String resourcePrefix;
    private final String mount;
    private final String indexFile;
    private volatile String cacheControl;

    private volatile String cacheControlDocumento = "no-cache";
    private volatile String spaPagina;
    private final Map<String, byte[]> classpathCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHEADOS = 512;

    private StaticFiles(Path root, String resourcePrefix, String mount, String indexFile) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.resourcePrefix = resourcePrefix;
        this.mount = mount;
        this.indexFile = indexFile;
    }

    public static StaticFiles from(Path root) {
        return from(root, "/", "index.html");
    }

    public static StaticFiles from(Path root, String mount) {
        return from(root, mount, "index.html");
    }

    public static StaticFiles from(Path root, String mount, String indexFile) {
        return new StaticFiles(root, null, mount, indexFile);
    }

    public StaticFiles cacheControl(String valor) {
        this.cacheControl = valor;
        return this;
    }

    public StaticFiles cacheControlDocumento(String valor) {
        this.cacheControlDocumento = valor;
        return this;
    }

    public StaticFiles spa() {
        return spa(indexFile);
    }

    public StaticFiles spa(String pagina) {
        this.spaPagina = pagina;
        return this;
    }

    public static StaticFiles fromClasspath(String prefix) {
        return fromClasspath(prefix, "/", "index.html");
    }

    public static StaticFiles fromClasspath(String prefix, String mount) {
        return fromClasspath(prefix, mount, "index.html");
    }

    public static StaticFiles fromClasspath(String prefix, String mount, String indexFile) {
        String head = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
        return new StaticFiles(null, head, mount, indexFile);
    }

    @Override
    public void handle(Request request, Response response) throws IOException {
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
            if (respaldoSpa(request, relative)) {
                target = resolve(spaPagina);
                if (target != null && Files.isRegularFile(target)) {
                    String tipo = MimeTypes.of(spaPagina);
                    cabeceraDeCache(response, tipo);
                    response.type(tipo);
                    response.send(Files.readAllBytes(target));
                    return;
                }
            }
            response.status(404).text("no encontrado");
            return;
        }

        BasicFileAttributes attributes =
                Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isDirectory()) {
            target = target.resolve(indexFile);
            if (!Files.isRegularFile(target)) {
                response.status(404).text("no encontrado");
                return;
            }
            attributes = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
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
        String tipo = MimeTypes.of(target.getFileName().toString());
        cabeceraDeCache(response, tipo);
        response.type(tipo);

        long tamano = attributes.size();
        Ranges pedido = rangoPedido(request, etag, tamano);
        if (pedido == Ranges.NO_SATISFACIBLE) {
            response.status(416).header("Content-Range", "bytes */" + tamano).send(new byte[0]);
            return;
        }
        if (pedido != null) {
            response.status(206).header("Content-Range", pedido.contentRange(tamano));
            if (pedido.longitud() > STREAM_THRESHOLD) {
                try (OutputStream out = response.stream()) {
                    transmitirTrozo(target, pedido, out);
                }
            } else {
                response.send(leerTrozo(target, pedido));
            }
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

    private void cabeceraDeCache(Response response, String tipo) {
        boolean documento = tipo != null && tipo.startsWith("text/html");
        String valor = documento ? cacheControlDocumento : cacheControl;
        if (valor != null) {
            response.header("Cache-Control", valor);
        }
    }

    private static Ranges rangoPedido(Request request, String etag, long tamano) {
        String condicion = request.header("If-Range");
        if (condicion != null && !condicion.trim().equals(etag)) {
            return null;
        }
        return Ranges.parse(request.header("Range"), tamano);
    }

    private static void transmitirTrozo(Path archivo, Ranges rango, OutputStream destino)
            throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long quedan = rango.longitud();
        try (InputStream entrada = Files.newInputStream(archivo)) {
            long saltado = entrada.skip(rango.desde());
            if (saltado < rango.desde()) {
                return;
            }
            while (quedan > 0) {
                int leidos = entrada.read(buffer, 0, (int) Math.min(buffer.length, quedan));
                if (leidos < 0) {
                    return;
                }
                destino.write(buffer, 0, leidos);
                quedan -= leidos;
            }
        }
    }

    private static byte[] leerTrozo(Path archivo, Ranges rango) throws IOException {
        long longitud = rango.longitud();
        byte[] trozo = new byte[(int) longitud];
        try (SeekableByteChannel canal = Files.newByteChannel(archivo)) {
            canal.position(rango.desde());
            ByteBuffer destino = ByteBuffer.wrap(trozo);
            while (destino.hasRemaining() && canal.read(destino) > 0) {
                continue;
            }
            return destino.hasRemaining()
                    ? Arrays.copyOf(trozo, destino.position())
                    : trozo;
        }
    }

    private void serveFromClasspath(Request request, Response response, String relative) {
        String name = relative.isEmpty() ? indexFile : relative;

        String withIndex = name.endsWith("/") ? name + indexFile : name + "/" + indexFile;
        if (classpathExists(withIndex)) {
            name = withIndex;
        } else if (!classpathExists(name)) {
            if (respaldoSpa(request, relative) && classpathExists(spaPagina)) {
                String tipo = MimeTypes.of(spaPagina);
                cabeceraDeCache(response, tipo);
                response.type(tipo);
                response.send(cachear(spaPagina));
                return;
            }
            response.status(404).text("no encontrado");
            return;
        }
        byte[] content = cachear(name);

        String etag = "\"" + Integer.toHexString(Arrays.hashCode(content)) + "\"";
        if (notModified(request, response, etag)) {
            return;
        }
        response.header("ETag", etag);
        response.header("Accept-Ranges", "bytes");
        String tipo = MimeTypes.of(name);
        cabeceraDeCache(response, tipo);
        response.type(tipo);

        Ranges pedido = rangoPedido(request, etag, content.length);
        if (pedido == Ranges.NO_SATISFACIBLE) {
            response.status(416).header("Content-Range", "bytes */" + content.length).send(new byte[0]);
            return;
        }
        if (pedido != null) {
            response.status(206).header("Content-Range", pedido.contentRange(content.length));
            response.send(Arrays.copyOfRange(content,
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

    private byte[] cachear(String name) {
        byte[] guardado = classpathCache.get(name);
        if (guardado != null) {
            return guardado;
        }
        byte[] contenido = readResource(name);
        if (classpathCache.size() < MAX_CACHEADOS && contenido.length <= STREAM_THRESHOLD) {
            classpathCache.putIfAbsent(name, contenido);
        }
        return contenido;
    }

    int cacheados() {
        return classpathCache.size();
    }

    private byte[] readResource(String name) {
        try (InputStream source = loader().getResourceAsStream(resourcePrefix + name)) {
            return source == null ? new byte[0] : source.readAllBytes();
        } catch (IOException cause) {
            return new byte[0];
        }
    }

    private boolean respaldoSpa(Request request, String relative) {
        if (spaPagina == null) {
            return false;
        }
        String ultimo = relative;
        int barra = ultimo.lastIndexOf('/');
        if (barra >= 0) {
            ultimo = ultimo.substring(barra + 1);
        }
        if (ultimo.indexOf('.') >= 0) {
            String acepta = request.header("Accept");
            return acepta != null && acepta.contains("text/html");
        }
        return true;
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
