package lux.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Arma un proyecto LuxCore listo para arrancar y lo devuelve como ZIP en memoria.
 *
 * <p>El generador anterior emitía un WAR con {@code web.xml} y JSP, que era lo que JxMVC
 * necesitaba. Este emite lo que necesita LuxCore: un jar que arranca solo.
 */
public final class GeneradorProyecto {

    public record Peticion(String grupo, String artefacto, String paquete, String nombre, String motor) {

        /** Limpia lo que venga del formulario y rellena lo que falte. */
        public static Peticion de(String grupo, String artefacto, String nombre, String motor) {
            String grupoLimpio = coordenada(grupo, "com.ejemplo");
            String artefactoLimpio = coordenada(artefacto, "mi-app");
            return new Peticion(grupoLimpio, artefactoLimpio,
                    paqueteValido(grupoLimpio + "." + artefactoLimpio.replaceAll("[^a-zA-Z0-9]", "")),
                    visible(nombre, artefactoLimpio),
                    motorValido(motor));
        }
    }

    private GeneradorProyecto() {
    }

    public static byte[] construir(Peticion peticion) throws Exception {
        String ruta = peticion.paquete().replace('.', '/');
        ByteArrayOutputStream salida = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(salida)) {
            anadir(zip, "pom.xml", pom(peticion));
            anadir(zip, "README.md", readme(peticion));
            anadir(zip, ".gitignore", "target/\n*.class\n.env\n");
            anadir(zip, "src/main/resources/application.properties", propiedades(peticion));
            anadir(zip, "src/main/java/" + ruta + "/App.java", app(peticion));
            anadir(zip, "src/main/java/" + ruta + "/InicioController.java", controlador(peticion));
            anadir(zip, "src/main/resources/plantillas/base.html", base(peticion));
            anadir(zip, "src/main/resources/plantillas/inicio.html", inicio(peticion));
            anadir(zip, "src/main/resources/estaticos/estilo.css", estilo());
        }
        return salida.toByteArray();
    }

    private static void anadir(ZipOutputStream zip, String nombre, String contenido) throws Exception {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(contenido.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String pom(Peticion p) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                    <name>%s</name>

                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>lux</groupId>
                            <artifactId>lux-core</artifactId>
                            <version>0.2.0</version>
                        </dependency>
                        <dependency>
                            <groupId>lux</groupId>
                            <artifactId>lux-view</artifactId>
                            <version>0.2.0</version>
                        </dependency>
                %s    </dependencies>

                    <build>
                        <finalName>%s</finalName>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.13.0</version>
                                <configuration>
                                    <compilerArgs><arg>-parameters</arg></compilerArgs>
                                </configuration>
                            </plugin>

                            <!-- Las dos cosas que hacen que `java -jar` funcione: las dependencias
                                 al lado del jar, y un manifiesto que las nombre. -->
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-dependency-plugin</artifactId>
                                <version>3.6.1</version>
                                <executions>
                                    <execution>
                                        <phase>package</phase>
                                        <goals><goal>copy-dependencies</goal></goals>
                                        <configuration>
                                            <outputDirectory>${project.build.directory}/lib</outputDirectory>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-jar-plugin</artifactId>
                                <version>3.4.1</version>
                                <configuration>
                                    <archive>
                                        <manifest>
                                            <mainClass>%s.App</mainClass>
                                            <addClasspath>true</addClasspath>
                                            <classpathPrefix>lib/</classpathPrefix>
                                        </manifest>
                                    </archive>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(p.grupo(), p.artefacto(), p.nombre(), dependenciasDeDatos(p.motor()),
                p.artefacto(), p.paquete());
    }

    private static String dependenciasDeDatos(String motor) {
        if (motor.equals("ninguno")) {
            return "";
        }
        String datos = """
                        <dependency>
                            <groupId>lux</groupId>
                            <artifactId>lux-data</artifactId>
                            <version>0.2.0</version>
                        </dependency>
                """;
        return datos + switch (motor) {
            case "postgresql" -> """
                            <dependency>
                                <groupId>org.postgresql</groupId>
                                <artifactId>postgresql</artifactId>
                                <version>42.7.4</version>
                            </dependency>
                    """;
            case "mysql" -> """
                            <dependency>
                                <groupId>com.mysql</groupId>
                                <artifactId>mysql-connector-j</artifactId>
                                <version>9.1.0</version>
                            </dependency>
                    """;
            default -> """
                            <dependency>
                                <groupId>com.h2database</groupId>
                                <artifactId>h2</artifactId>
                                <version>2.2.224</version>
                            </dependency>
                    """;
        };
    }

    private static String propiedades(Peticion p) {
        String base = "server.port = 8080\n";
        return base + switch (p.motor()) {
            case "postgresql" -> "db.url = jdbc:postgresql://localhost:5432/" + p.artefacto() + "\n"
                    + "db.user = postgres\ndb.password =\n";
            case "mysql" -> "db.url = jdbc:mysql://localhost:3306/" + p.artefacto() + "\n"
                    + "db.user = root\ndb.password =\n";
            case "h2" -> "db.url = jdbc:h2:mem:" + p.artefacto() + ";DB_CLOSE_DELAY=-1\n";
            default -> "";
        };
    }

    private static String app(Peticion p) {
        return """
                package %s;

                import lux.core.Lux;
                import lux.http.StaticFiles;
                import lux.view.Templates;

                public final class App {

                    public static void main(String[] args) throws Exception {
                        Lux.app()
                           .port(args.length > 0 ? Integer.parseInt(args[0]) : 8080)
                           .views(Templates.fromClasspath("plantillas").suffix(".html"))
                           .fallback(StaticFiles.fromClasspath("estaticos", "/estaticos"))
                           .controllers(InicioController.class)
                           .start()
                           .await();
                    }
                }
                """.formatted(p.paquete());
    }

    private static String controlador(Peticion p) {
        return """
                package %s;

                import lux.core.Get;
                import lux.core.Result;
                import lux.core.Route;

                import java.util.Map;

                @Route("/")
                public class InicioController {

                    @Get("")
                    public Result inicio() {
                        return Result.view("inicio", Map.of("titulo", "%s"));
                    }

                    @Get("/salud")
                    public Result salud() {
                        return Result.text("ok");
                    }
                }
                """.formatted(p.paquete(), p.nombre());
    }

    private static String base(Peticion p) {
        return """
                <!doctype html>
                <html lang="es">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>{%% block titulo %%}%s{%% end %%}</title>
                    <link rel="stylesheet" href="/estaticos/estilo.css">
                </head>
                <body>
                    <main>{%% block contenido %%}{%% end %%}</main>
                </body>
                </html>
                """.formatted(p.nombre());
    }

    private static String inicio(Peticion p) {
        return """
                {%% extends "base" %%}

                {%% block titulo %%}{{ titulo }}{%% end %%}

                {%% block contenido %%}
                <h1>%s</h1>
                <p>Funcionando sobre LuxCore, sin contenedor y sin dependencias externas.</p>
                <p>Edita <code>InicioController.java</code> y recarga.</p>
                {%% end %%}
                """.formatted(p.nombre());
    }

    private static String estilo() {
        return """
                :root { color-scheme: light dark; }
                body { font: 16px/1.6 system-ui, sans-serif; max-width: 42rem; margin: 4rem auto; padding: 0 1rem; }
                h1 { letter-spacing: -0.02em; }
                code { background: rgba(127,127,127,.15); padding: .1em .35em; border-radius: .25rem; }
                """;
    }

    private static String readme(Peticion p) {
        return """
                # %s

                Proyecto generado con LuxCore.

                ```bash
                mvn package
                java -jar target/%s.jar        # y en otro puerto:  java -jar target/%s.jar 9090
                ```

                Arranca en el puerto 8080. Sin contenedor de servlets y sin dependencias
                externas más allá del driver JDBC si usas base de datos.

                Necesita LuxCore en tu repositorio local de Maven:

                ```bash
                curl -fsSL https://luxcore.ginit.dev/instalar | sh
                ```
                """.formatted(p.nombre(), p.artefacto(), p.artefacto());
    }

    private static String coordenada(String valor, String porDefecto) {
        if (valor == null || valor.isBlank()) {
            return porDefecto;
        }
        String limpio = valor.trim().toLowerCase().replaceAll("[^a-z0-9.\\-]", "");
        return limpio.isBlank() ? porDefecto : limpio;
    }

    /** Nombre visible: letras, números, espacio y {@code . _ -}, con tope de 60. */
    private static String visible(String valor, String porDefecto) {
        if (valor == null) {
            return porDefecto;
        }
        String limpio = valor.trim().replaceAll("[^\\p{L}\\p{N} ._-]", "").trim();
        if (limpio.length() > 60) {
            limpio = limpio.substring(0, 60).trim();
        }
        return limpio.isBlank() ? porDefecto : limpio;
    }

    private static String motorValido(String valor) {
        return switch (valor == null ? "" : valor.trim().toLowerCase()) {
            case "postgresql", "mysql", "h2" -> valor.trim().toLowerCase();
            default -> "ninguno";
        };
    }

    /** Un nombre de paquete Java válido: segmentos alfanuméricos que empiezan por letra. */
    private static String paqueteValido(String candidato) {
        StringBuilder paquete = new StringBuilder();
        for (String parte : candidato.split("\\.")) {
            String segmento = parte.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (segmento.isEmpty()) {
                continue;
            }
            if (!Character.isLetter(segmento.charAt(0))) {
                segmento = "p" + segmento;
            }
            if (paquete.length() > 0) {
                paquete.append('.');
            }
            paquete.append(segmento);
        }
        return paquete.isEmpty() ? "com.ejemplo.app" : paquete.toString();
    }
}
