package cero.launcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** {@code cero new} — arma un proyecto nuevo y lo escribe en disco. */
public final class Nuevo {

    private static final java.util.Set<String> MOTORES =
            java.util.Set.of("ninguno", "h2", "postgresql", "mysql");

    private Nuevo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].startsWith("-")) {
            System.err.println(Idioma.t("nuevo-uso"));
            System.exit(1);
        }

        List<String> libres = new ArrayList<>();
        boolean conFrontend = false;
        for (String argumento : args) {
            if (argumento.equals("--front")) {
                conFrontend = true;
            } else {
                libres.add(argumento);
            }
        }

        String artefacto = libres.get(0);
        String grupo = libres.size() > 1 ? libres.get(1) : "com.ejemplo";
        String motor = libres.size() > 2 ? libres.get(2) : "ninguno";

        // "cero new tienda h2" es lo que sale solo; sin esto, h2 acabaría siendo el groupId.
        if (libres.size() == 2 && MOTORES.contains(libres.get(1))) {
            grupo = "com.ejemplo";
            motor = libres.get(1);
        }

        Path destino = Path.of(artefacto).toAbsolutePath();
        if (Files.exists(destino)) {
            System.err.println(Idioma.t("nuevo-existe", destino));
            System.exit(1);
        }

        var peticion = GeneradorProyecto.Peticion.de(grupo, artefacto, artefacto, motor);
        Path raizJava = conFrontend ? destino.resolve("backend") : destino;
        int archivos = descomprimir(GeneradorProyecto.construir(peticion), raizJava);

        if (conFrontend) {
            archivos += Frontend.escribir(destino, peticion);
            Frontend.cablearBackend(raizJava, peticion);
        }

        System.out.println("  " + destino);
        System.out.println("  " + Idioma.t("nuevo-archivos", archivos) + " · " + peticion.grupo() + ":"
                + peticion.artefacto() + (peticion.motor().equals("ninguno") ? "" : " · " + peticion.motor())
                + (conFrontend ? " · " + Idioma.t("nuevo-ambos") : ""));
        System.out.println();
        if (conFrontend) {
            System.out.println("    cd " + artefacto + "/backend");
            System.out.println("    mvn -q package && java -Xmx64m -jar target/" + peticion.artefacto() + ".jar");
            System.out.println();
            System.out.println("  " + Idioma.t("nuevo-front-1"));
            System.out.println("  " + Idioma.t("nuevo-front-2"));
            System.out.println("  " + Idioma.t("nuevo-front-3"));
        } else {
            System.out.println("    cd " + artefacto);
            System.out.println("    mvn -q package && java -Xmx64m -jar target/" + peticion.artefacto() + ".jar");
        }
        System.out.println();
        System.out.println("  " + Idioma.t("nuevo-xmx-1"));
        System.out.println("  " + Idioma.t("nuevo-xmx-2"));
        System.out.println("  " + Idioma.t("nuevo-xmx-3"));
    }

    private static int descomprimir(byte[] zip, Path destino) throws IOException {
        int archivos = 0;
        try (ZipInputStream entrada = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entrada2; (entrada2 = entrada.getNextEntry()) != null; ) {
                Path archivo = destino.resolve(entrada2.getName()).normalize();
                if (!archivo.startsWith(destino)) {
                    throw new IOException("entrada fuera del destino: " + entrada2.getName());
                }
                Files.createDirectories(archivo.getParent());
                try (OutputStream salida = Files.newOutputStream(archivo)) {
                    entrada.transferTo(salida);
                }
                archivos++;
            }
        }
        return archivos;
    }
}
