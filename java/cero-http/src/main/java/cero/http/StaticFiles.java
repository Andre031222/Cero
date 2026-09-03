package cero.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StaticFiles implements Handler {

    private static final int STREAM_THRESHOLD = 1 << 20;

    private final Path root;
    private final String resourcePrefix;
    private final String mount;
    private final String indexFile;
    private volatile String cacheControl;
    private volatile String spaPagina;
    /**
     * Recursos del classpath ya leídos. Solo entran los que existen y caben: la clave la elige
     * quien hace la petición, así que guardar las ausencias dejaba que cualquiera llenara el
     * montón pidiendo rutas inventadas — dos entradas por cada 404, sin tope ni desalojo.
     */
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

    /**
     * Valor de {@code Cache-Control} para lo que sirva. Sin esto el navegador aplica su
     * heurística, que suele ser revalidar de más: un 304 por recurso y por visita. Para recursos
     * con huella en el nombre —{@code estilo.a1b2c3.css}— lo correcto es
     * {@code "public, max-age=31536000, immutable"}.
     */
    public StaticFiles cacheControl(String valor) {
        this.cacheControl = valor;
        return this;
    }

    /**
     * Respaldo para una aplicación de una sola página — React, Svelte, Vue— que enruta en el
     * cliente. Con ella, una ruta que no existe como archivo devuelve {@code index.html} y deja
     * que el navegador decida qué pintar; sin ella daría 404 y la aplicación solo funcionaría
     * entrando por la portada.
     *
     * <p>No se aplica a todo: solo cuando el cliente pide HTML o la ruta no tiene extensión. Un
     * {@code .css} o un {@code .png} que falten siguen dando 404 — devolverles el HTML de la
     * portada convierte un error evidente en uno que se tarda una hora en encontrar.
     *
     * <pre>{@code StaticFiles.fromClasspath("front").spa();}</pre>
     */
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
                    response.type(MimeTypes.of(spaPagina));
                    response.send(Files.readAllBytes(target));
                    return;
                }
            }
            response.status(404).text("no encontrado");
            return;
        }

        // NOFOLLOW igual que en la línea de arriba: sin esto readAttributes sigue el enlace,
        // y un symlink dentro de la raíz servida que apunte fuera se serviría sin más.
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
        if (cacheControl != null) {
            response.header("Cache-Control", cacheControl);
        }
        response.type(MimeTypes.of(target.getFileName().toString()));

        long tamano = attributes.size();
        Ranges pedido = rangoPedido(request, etag, tamano);
        if (pedido == Ranges.NO_SATISFACIBLE) {
            response.status(416).header("Content-Range", "bytes */" + tamano).send(new byte[0]);
            return;
        }
        if (pedido != null) {
            response.status(206).header("Content-Range", pedido.contentRange(tamano));
            // Antes esto recortaba a 1 MB DESPUÉS de anunciar el rango entero, así que cabecera y
            // cuerpo se contradecían y un vídeo o un PDF grande se cortaba a media descarga. Los
            // rangos grandes se transmiten, igual que ya se hacía con el archivo completo.
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

    /** El intervalo pedido, o {@code null} si hay que servir el recurso entero. */
    private static Ranges rangoPedido(Request request, String etag, long tamano) {
        String condicion = request.header("If-Range");
        if (condicion != null && !condicion.trim().equals(etag)) {
            return null;
        }
        return Ranges.parse(request.header("Range"), tamano);
    }

    /** Vuelca el intervalo pedido, por trozos, sin traérselo entero a memoria. */
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

        // Si existe ruta/index.html, gana: es la única forma de que "ruta" sea una carpeta, y
        // una carpeta del classpath también "existe" —leerla devuelve su listado—, así que sin
        // esto /nosotros respondía 200 con el nombre del archivo dentro en vez de la página.
        // Es justo lo que pide una exportación estática, donde cada sección es una carpeta.
        String withIndex = name.endsWith("/") ? name + indexFile : name + "/" + indexFile;
        if (classpathExists(withIndex)) {
            name = withIndex;
        } else if (!classpathExists(name)) {
            if (respaldoSpa(request, relative) && classpathExists(spaPagina)) {
                response.type(MimeTypes.of(spaPagina));
                response.send(cachear(spaPagina));
                return;
            }
            response.status(404).text("no encontrado");
            return;
        }
        byte[] content = cachear(name);

        String etag = "\"" + Integer.toHexString(java.util.Arrays.hashCode(content)) + "\"";
        if (notModified(request, response, etag)) {
            return;
        }
        response.header("ETag", etag);
        response.header("Accept-Ranges", "bytes");
        if (cacheControl != null) {
            response.header("Cache-Control", cacheControl);
        }
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

    /**
     * Lee el recurso, y lo guarda solo si el mapa no está lleno y no es enorme. Pasado el tope se
     * sirve igual, leyéndolo cada vez: mejor perder velocidad que crecer sin freno.
     */
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

    /** Cuántos recursos hay cacheados. Para las pruebas. */
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

    /** {@code true} si esta petición parece una página y no un recurso que falta. */
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
            // Tiene extensión: es un archivo que falta, no una ruta de la aplicación.
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
