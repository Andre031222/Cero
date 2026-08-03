package lux.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** {@code lux new} — el mismo generador del sitio, pero escribiendo en disco. */
public final class Nuevo {

    private Nuevo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].startsWith("-")) {
            System.err.println("""
                    uso:  lux new <artefacto> [grupo] [motor]

                      artefacto   nombre del proyecto y de la carpeta      (mi-app)
                      grupo       groupId de Maven                         (com.ejemplo)
                      motor       ninguno | h2 | postgresql | mysql        (ninguno)""");
            System.exit(1);
        }

        String artefacto = args[0];
        String grupo = args.length > 1 ? args[1] : "com.ejemplo";
        String motor = args.length > 2 ? args[2] : "ninguno";

        Path destino = Path.of(artefacto).toAbsolutePath();
        if (Files.exists(destino)) {
            System.err.println("ya existe " + destino + " — elige otro nombre o bórralo");
            System.exit(1);
        }

        var peticion = GeneradorProyecto.Peticion.de(grupo, artefacto, artefacto, motor);
        int archivos = descomprimir(GeneradorProyecto.construir(peticion), destino);

        System.out.println("  " + destino);
        System.out.println("  " + archivos + " archivos · " + peticion.grupo() + ":"
                + peticion.artefacto() + (peticion.motor().equals("ninguno") ? "" : " · " + peticion.motor()));
        System.out.println();
        System.out.println("    cd " + artefacto);
        System.out.println("    mvn -q package && java -jar target/" + peticion.artefacto() + ".jar");
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
