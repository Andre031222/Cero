package lux.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

public final class TestSuite {

    private static int pasadas;
    private static int fallidas;

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("── lux-launcher, el jar que arranca solo");

        Path salida = Path.of("target", "prueba-app.jar");
        Files.deleteIfExists(salida);

        List<Path> entradas = new ArrayList<>(delClasspath());

        int total = Packager.conMain("lux.launcher.Servidor").anadir(entradas).escribir(salida);

        comprobar("el jar se escribe", Files.exists(salida));
        comprobar("con entradas dentro", total > 100);

        try (JarFile jar = new JarFile(salida.toFile())) {
            comprobar("declara la clase principal",
                    "lux.launcher.Servidor".equals(jar.getManifest()
                            .getMainAttributes().getValue("Main-Class")));
            comprobar("trae la clase de la aplicación",
                    jar.getEntry("lux/launcher/Servidor.class") != null);
            comprobar("y las clases del framework",
                    jar.getEntry("lux/http/Server.class") != null);
            comprobar("sin firmas heredadas de los jar de origen",
                    jar.stream().noneMatch(e -> e.getName().toUpperCase().endsWith(".SF")));
            comprobar("y sin los manifiestos originales",
                    jar.stream().filter(e -> e.getName().equals("META-INF/MANIFEST.MF")).count() <= 1);
        }

        arrancaSolo(salida);

        comprobar("sin clase principal no se empaqueta", lanza(() -> Packager.conMain("  ")));
        comprobar("sin entradas tampoco", lanza(() -> {
            try {
                Packager.conMain("X").escribir(Path.of("target", "vacio.jar"));
            } catch (IOException imposible) {
                throw new IllegalStateException(imposible);
            }
        }));

        System.out.println();
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("  TOTAL  pass=%d  fail=%d%n", pasadas, fallidas);
        if (fallidas > 0) {
            System.exit(1);
        }
    }

    /** La prueba de verdad: {@code java -jar}, sin classpath y sin nada instalado. */
    private static void arrancaSolo(Path jar) throws Exception {
        Process proceso = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "18123")
                .redirectErrorStream(true)
                .start();
        try {
            String cuerpo = null;
            for (int intento = 0; intento < 100 && cuerpo == null; intento++) {
                try {
                    cuerpo = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:18123/"))
                                    .version(HttpClient.Version.HTTP_1_1).build(),
                            HttpResponse.BodyHandlers.ofString()).body();
                } catch (IOException todavia) {
                    Thread.sleep(100);
                }
            }
            comprobar("java -jar arranca el servidor y responde", "empaquetado".equals(cuerpo));
        } finally {
            proceso.destroy();
            proceso.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Todo lo que Maven puso en el classpath: jar del repositorio y directorios de clases de los
     * módulos del propio reactor. Empaquetar solo los jar dejaría fuera medio framework.
     */
    private static List<Path> delClasspath() {
        List<Path> entradas = new ArrayList<>();
        for (String parte : System.getProperty("java.class.path")
                .split(System.getProperty("path.separator"))) {
            Path candidato = Path.of(parte);
            if (Files.exists(candidato)) {
                entradas.add(candidato);
            }
        }
        return entradas;
    }

    private static boolean lanza(Runnable accion) {
        try {
            accion.run();
            return false;
        } catch (RuntimeException esperado) {
            return true;
        }
    }

    private static void comprobar(String nombre, boolean condicion) {
        if (condicion) {
            pasadas++;
            System.out.println("  OK  " + nombre);
        } else {
            fallidas++;
            System.out.println("  XX  " + nombre);
        }
    }

}
