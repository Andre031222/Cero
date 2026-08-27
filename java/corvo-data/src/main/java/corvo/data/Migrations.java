package corvo.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Aplica los archivos {@code .sql} de un directorio, en orden y una sola vez.
 *
 * <pre>{@code
 * int aplicadas = Migrations.from(Path.of("db/migraciones")).run();
 * }</pre>
 *
 * <p>Cada archivo corre dentro de su propia transacción y queda anotado en una tabla con su
 * huella. Si un archivo ya aplicado cambia de contenido, {@link #run()} falla en vez de
 * ignorarlo: editar una migración ya corrida deja dos entornos distintos creyéndose iguales, y
 * eso solo se descubre cuando algo se rompe en producción.
 *
 * <h2>Hasta dónde llega la transacción</h2>
 *
 * <p>Una migración que falla <b>nunca queda anotada</b>, así que se reintenta en la siguiente
 * corrida. Lo que sí depende del motor es si sus efectos se deshacen:
 *
 * <ul>
 *   <li><b>PostgreSQL</b> tiene DDL transaccional: un {@code create table} a medias se deshace y
 *       la base queda como estaba.</li>
 *   <li><b>MySQL, H2, Oracle</b> confirman de forma implícita en cada DDL. Si una migración crea
 *       dos tablas y falla en la segunda, la primera <b>se queda creada</b>.</li>
 * </ul>
 *
 * <p>Ahí la regla es <b>una migración, un cambio</b>: así, cuando una falla, o no hizo nada o lo
 * hizo entero. Es la misma limitación que tienen Flyway y Liquibase, y por el mismo motivo — no
 * es algo que una herramienta pueda arreglar.
 */
public final class Migrations {

    private final String fuente;
    private final Origen origen;
    private String tabla = "corvo_migraciones";

    private Migrations(String fuente, Origen origen) {
        this.fuente = fuente;
        this.origen = origen;
    }

    public static Migrations from(Path directorio) {
        return new Migrations(DataSources.DEFAULT, new EnDisco(directorio));
    }

    public static Migrations from(String fuente, Path directorio) {
        return new Migrations(fuente, new EnDisco(directorio));
    }

    public static Migrations fromClasspath(String prefijo) {
        return new Migrations(DataSources.DEFAULT, new EnClasspath(prefijo));
    }

    public Migrations table(String nombre) {
        this.tabla = nombre;
        return this;
    }

    /** Aplica lo que falte y devuelve cuántas se aplicaron. */
    public int run() {
        Db db = Db.open(fuente);
        crearTablaDeControl(db);

        int aplicadas = 0;
        for (Migracion migracion : origen.leer()) {
            Row anotada = db.one("select huella from " + tabla + " where nombre = ?", migracion.nombre);
            if (anotada != null) {
                comprobarHuella(migracion, String.valueOf(anotada.get("huella")));
                continue;
            }
            aplicar(migracion);
            aplicadas++;
        }
        return aplicadas;
    }

    /** Las ya aplicadas, en el orden en que se aplicaron. */
    public List<String> applied() {
        Db db = Db.open(fuente);
        crearTablaDeControl(db);
        List<String> nombres = new ArrayList<>();
        for (Row fila : db.query("select nombre from " + tabla + " order by aplicada_en, nombre")) {
            nombres.add(String.valueOf(fila.get("nombre")));
        }
        return nombres;
    }

    private void crearTablaDeControl(Db db) {
        db.exec("create table if not exists " + tabla + " ("
                + "nombre varchar(255) primary key, "
                + "huella varchar(64) not null, "
                + "aplicada_en bigint not null)");
    }

    private void aplicar(Migracion migracion) {
        Tx.run(() -> {
            Db db = Db.open(fuente);
            for (String sentencia : sentencias(migracion.sql)) {
                db.exec(sentencia);
            }
            db.exec("insert into " + tabla + " (nombre, huella, aplicada_en) values (?, ?, ?)",
                    migracion.nombre, migracion.huella, System.currentTimeMillis());
        });
    }

    private static void comprobarHuella(Migracion migracion, String anotada) {
        if (!migracion.huella.equals(anotada)) {
            throw new DataException("la migración " + migracion.nombre + " ya se aplicó, pero su"
                    + " contenido cambió desde entonces.\n      aplicada  " + anotada
                    + "\n      ahora     " + migracion.huella
                    + "\n  Escribe una migración nueva en vez de editar esta.");
        }
    }

    /**
     * Parte por punto y coma. No se hace en el driver porque muchos no admiten varias sentencias
     * en un mismo {@code execute}, y una migración casi siempre son varias.
     */
    private static List<String> sentencias(String sql) {
        List<String> partes = new ArrayList<>();
        for (String parte : sql.split(";")) {
            String limpia = sinComentarios(parte).trim();
            if (!limpia.isEmpty()) {
                partes.add(limpia);
            }
        }
        return partes;
    }

    private static String sinComentarios(String sql) {
        StringBuilder limpio = new StringBuilder();
        for (String linea : sql.split("\n")) {
            String sinGuiones = linea.replaceFirst("--.*$", "");
            limpio.append(sinGuiones).append('\n');
        }
        return limpio.toString();
    }

    private static String huellaDe(String contenido) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(contenido.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException(imposible);
        }
    }

    private record Migracion(String nombre, String sql, String huella) {

        static Migracion de(String nombre, String sql) {
            return new Migracion(nombre, sql, huellaDe(sql));
        }
    }

    private interface Origen {
        List<Migracion> leer();
    }

    private record EnDisco(Path directorio) implements Origen {

        @Override
        public List<Migracion> leer() {
            if (!Files.isDirectory(directorio)) {
                throw new DataException("no existe el directorio de migraciones: " + directorio);
            }
            try (var archivos = Files.list(directorio)) {
                return archivos
                        .filter(a -> a.getFileName().toString().endsWith(".sql"))
                        .sorted(Comparator.comparing(a -> a.getFileName().toString()))
                        .map(a -> Migracion.de(a.getFileName().toString(), leerTexto(a)))
                        .toList();
            } catch (IOException fallo) {
                throw new UncheckedIOException(fallo);
            }
        }

        private static String leerTexto(Path archivo) {
            try {
                return Files.readString(archivo, StandardCharsets.UTF_8);
            } catch (IOException fallo) {
                throw new UncheckedIOException(fallo);
            }
        }
    }

    /**
     * Dentro de un jar no se puede listar un directorio del classpath, así que las migraciones se
     * enumeran en un {@code indice.txt} junto a ellas, un nombre por línea.
     */
    private record EnClasspath(String prefijo) implements Origen {

        @Override
        public List<Migracion> leer() {
            String base = prefijo.endsWith("/") ? prefijo : prefijo + "/";
            String indice = leerRecurso(base + "indice.txt");
            if (indice == null) {
                throw new DataException("falta " + base + "indice.txt con la lista de migraciones");
            }
            List<Migracion> migraciones = new ArrayList<>();
            for (String linea : indice.split("\n")) {
                String nombre = linea.trim();
                if (nombre.isEmpty() || nombre.startsWith("#")) {
                    continue;
                }
                String sql = leerRecurso(base + nombre);
                if (sql == null) {
                    throw new DataException("el índice nombra " + nombre + ", que no existe");
                }
                migraciones.add(Migracion.de(nombre, sql));
            }
            return migraciones;
        }

        private static String leerRecurso(String ruta) {
            ClassLoader cargador = Thread.currentThread().getContextClassLoader();
            if (cargador == null) {
                cargador = Migrations.class.getClassLoader();
            }
            try (InputStream entrada = cargador.getResourceAsStream(ruta)) {
                return entrada == null ? null : new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException fallo) {
                throw new UncheckedIOException(fallo);
            }
        }
    }
}
