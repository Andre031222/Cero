package corvo.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Junta la aplicación y sus jar en uno solo, ejecutable con {@code java -jar app.jar}.
 *
 * <pre>
 *   java -cp corvo-launcher.jar corvo.launcher.Packager \
 *        --main ejemplo.App --out app.jar target/classes lib/*.jar
 * </pre>
 *
 * <p>Usa {@code java.util.jar} del JDK: empaquetar sin un plugin de terceros es coherente con un
 * framework que presume de no tener dependencias.
 *
 * <p>Las firmas de los jar de entrada se descartan — al mezclar contenidos dejan de ser válidas y
 * la JVM rechazaría el resultado. Si alguna de tus dependencias necesita conservar su firma, este
 * empaquetado no te sirve.
 */
public final class Packager {

    private final String mainClass;
    private final List<Path> entradas = new ArrayList<>();

    private Packager(String mainClass) {
        this.mainClass = mainClass;
    }

    public static Packager conMain(String mainClass) {
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException("hace falta la clase principal");
        }
        return new Packager(mainClass);
    }

    /** Añade un directorio de clases o un jar. */
    public Packager anadir(Path entrada) {
        entradas.add(entrada);
        return this;
    }

    public Packager anadir(List<Path> varias) {
        entradas.addAll(varias);
        return this;
    }

    /** @return cuántas entradas se escribieron */
    public int escribir(Path destino) throws IOException {
        if (entradas.isEmpty()) {
            throw new IllegalStateException("no hay nada que empaquetar");
        }
        Manifest manifiesto = new Manifest();
        manifiesto.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifiesto.getMainAttributes().putValue("Main-Class", mainClass);
        manifiesto.getMainAttributes().putValue("Created-By", "lux-launcher");

        Set<String> vistas = new HashSet<>();
        if (destino.getParent() != null) {
            Files.createDirectories(destino.getParent());
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(destino), manifiesto)) {
            for (Path entrada : entradas) {
                if (Files.isDirectory(entrada)) {
                    volcarDirectorio(jar, entrada, vistas);
                } else {
                    volcarJar(jar, entrada, vistas);
                }
            }
        }
        return vistas.size();
    }

    private static void volcarDirectorio(JarOutputStream jar, Path raiz, Set<String> vistas)
            throws IOException {

        Files.walkFileTree(raiz, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path archivo, BasicFileAttributes atributos)
                    throws IOException {
                String nombre = raiz.relativize(archivo).toString().replace('\\', '/');
                if (aceptable(nombre) && vistas.add(nombre)) {
                    jar.putNextEntry(new ZipEntry(nombre));
                    Files.copy(archivo, jar);
                    jar.closeEntry();
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void volcarJar(JarOutputStream salida, Path origen, Set<String> vistas)
            throws IOException {

        try (JarFile jar = new JarFile(origen.toFile())) {
            var entradas = jar.entries();
            while (entradas.hasMoreElements()) {
                JarEntry entrada = entradas.nextElement();
                String nombre = entrada.getName();
                if (entrada.isDirectory() || !aceptable(nombre) || !vistas.add(nombre)) {
                    continue;
                }
                salida.putNextEntry(new ZipEntry(nombre));
                try (InputStream contenido = jar.getInputStream(entrada)) {
                    contenido.transferTo((OutputStream) salida);
                }
                salida.closeEntry();
            }
        }
    }

    private static boolean aceptable(String nombre) {
        if (nombre.equals("META-INF/MANIFEST.MF") || nombre.equals("module-info.class")) {
            return false;
        }
        String mayusculas = nombre.toUpperCase(Locale.ROOT);
        return !(mayusculas.startsWith("META-INF/") && (mayusculas.endsWith(".SF")
                || mayusculas.endsWith(".DSA") || mayusculas.endsWith(".RSA")
                || mayusculas.startsWith("META-INF/SIG-")));
    }

    public static void main(String[] args) throws IOException {
        String main = null;
        Path destino = Path.of("app.jar");
        List<Path> entradas = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--main" -> main = args[++i];
                case "--out" -> destino = Path.of(args[++i]);
                default -> entradas.add(Path.of(args[i]));
            }
        }
        if (main == null || entradas.isEmpty()) {
            System.err.println("uso: Packager --main <clase> [--out app.jar] <clases|jar>...");
            System.exit(2);
            return;
        }
        int total = conMain(main).anadir(entradas).escribir(destino);
        System.out.printf("%s · %d entradas · %.0f KB%n",
                destino, total, Files.size(destino) / 1024.0);
    }
}
