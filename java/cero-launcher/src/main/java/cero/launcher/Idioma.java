package cero.launcher;

import java.util.Locale;
import java.util.Map;

/**
 * Los textos que la orden {@code cero} imprime, en castellano y en inglés.
 *
 * <p>El guion {@code cero} y los dos instaladores ya eligen idioma igual: {@code CERO_LANG}
 * manda, y si no está se mira el entorno. Aquí se repite esa regla para que las tres piezas
 * digan lo mismo en la misma terminal.
 *
 * <p>Sin traducción se cae al castellano: un hueco tiene que verse raro, no quedarse en blanco.
 */
final class Idioma {

    private static final boolean INGLES = !esCastellano();

    private Idioma() {
    }

    private static boolean esCastellano() {
        String elegido = System.getenv("CERO_LANG");
        if (elegido == null || elegido.isBlank()) {
            elegido = System.getenv("LC_ALL");
        }
        if (elegido == null || elegido.isBlank()) {
            elegido = System.getenv("LC_MESSAGES");
        }
        if (elegido == null || elegido.isBlank()) {
            elegido = System.getenv("LANG");
        }
        if (elegido == null || elegido.isBlank()) {
            elegido = Locale.getDefault().getLanguage();
        }
        return elegido.toLowerCase(Locale.ROOT).startsWith("es");
    }

    /** El texto de {@code clave}, ya con los {@code %s} sustituidos. */
    static String t(String clave, Object... partes) {
        String plantilla = INGLES ? EN.get(clave) : null;
        if (plantilla == null) {
            plantilla = ES.get(clave);
        }
        if (plantilla == null) {
            plantilla = clave;
        }
        return partes.length == 0 ? plantilla : String.format(plantilla, partes);
    }

    private static final Map<String, String> ES = Map.ofEntries(
            Map.entry("nuevo-uso", """
                    uso:  cero new <nombre> [grupo] [motor] [--front]

                      nombre   nombre del proyecto y de la carpeta      (mi-app)
                      grupo    groupId de Maven                         (com.ejemplo)
                      motor    ninguno | h2 | postgresql | mysql        (ninguno)
                      --front  separa en backend/ y frontend/, listo para React,
                               Svelte o Vue, con la API sirviendo JSON

                      cero new tienda                       sin base de datos
                      cero new tienda h2                    con H2
                      cero new tienda pe.unap mysql         grupo propio y MySQL
                      cero new tienda h2 --front            con carpeta para el frontend"""),
            Map.entry("nuevo-existe", "ya existe %s — elige otro nombre o bórralo"),
            Map.entry("nuevo-archivos", "%d archivos"),
            Map.entry("nuevo-ambos", "backend + frontend"),
            Map.entry("nuevo-front-1", "El frontend va en frontend/. Cuando lo compiles, su salida se"),
            Map.entry("nuevo-front-2", "copia a backend/src/main/resources/front/ y sale un solo jar."),
            Map.entry("nuevo-front-3", "Lo explica frontend/LEEME.md."),
            Map.entry("nuevo-xmx-1", "El -Xmx64m no es un adorno: sin tope la JVM toma el 25 % de la"),
            Map.entry("nuevo-xmx-2", "memoria de la máquina y no la devuelve mientras haya carga. Con 64 MB"),
            Map.entry("nuevo-xmx-3", "el rendimiento no cambia y el proceso ocupa 131 MB en vez de 194."),
            Map.entry("mig-sin-base", "no se pudo abrir la base desechable %s: %s"),
            Map.entry("mig-driver", "el driver JDBC va en el classpath: CERO_JDBC_JAR=/ruta/driver.jar"),
            Map.entry("paq-uso", "uso: Packager --main <clase> [--out app.jar] <clases|jar>..."),
            Map.entry("paq-entradas", "%s · %d entradas · %.0f KB"),
            Map.entry("front-leeme", "Aquí se copia lo que compile el frontend.\n"));

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("nuevo-uso", """
                    usage:  cero new <name> [group] [engine] [--front]

                      name     project and directory name               (my-app)
                      group    Maven groupId                            (com.ejemplo)
                      engine   ninguno | h2 | postgresql | mysql        (ninguno)
                      --front  splits into backend/ and frontend/, ready for React,
                               Svelte or Vue, with the API serving JSON

                      cero new shop                         without a database
                      cero new shop h2                      with H2
                      cero new shop pe.unap mysql           your own group and MySQL
                      cero new shop h2 --front              with a frontend directory"""),
            Map.entry("nuevo-existe", "%s already exists — pick another name or delete it"),
            Map.entry("nuevo-archivos", "%d files"),
            Map.entry("nuevo-ambos", "backend + frontend"),
            Map.entry("nuevo-front-1", "The frontend lives in frontend/. When you build it, its output is"),
            Map.entry("nuevo-front-2", "copied to backend/src/main/resources/front/ and one jar comes out."),
            Map.entry("nuevo-front-3", "frontend/LEEME.md explains it."),
            Map.entry("nuevo-xmx-1", "The -Xmx64m is not decoration: without a cap the JVM takes 25 % of the"),
            Map.entry("nuevo-xmx-2", "machine's memory and does not give it back while there is load. With 64 MB"),
            Map.entry("nuevo-xmx-3", "throughput does not change and the process uses 131 MB instead of 194."),
            Map.entry("mig-sin-base", "could not open the throwaway database %s: %s"),
            Map.entry("mig-driver", "the JDBC driver goes on the classpath: CERO_JDBC_JAR=/path/driver.jar"),
            Map.entry("paq-uso", "usage: Packager --main <class> [--out app.jar] <classes|jar>..."),
            Map.entry("paq-entradas", "%s · %d entries · %.0f KB"),
            Map.entry("front-leeme", "Whatever the frontend build produces is copied here.\n"));

}
