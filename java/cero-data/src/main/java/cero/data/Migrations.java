package cero.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * eso solo se descubre cuando algo se rompe en producción. Si la corrección es legítima y no
 * altera lo que la migración dejó en el esquema, {@link #repair()} reescribe la huella.
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

    private static final String CLAVE_FORANEA = "23503";
    private static final Pattern FILA_AUSENTE =
            Pattern.compile("Key \\((.+?)\\)=\\((.+?)\\) is not present in table \"(.+?)\"");

    private final String fuente;
    private final Origen origen;
    private String tabla = "cero_migraciones";

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

    /**
     * Recalcula la huella de las ya aplicadas y reescribe las que hayan cambiado, sin reejecutar
     * nada. Es la salida para una migración corregida que no altera lo que dejó en el esquema:
     * sin esto, el arranque se para en todos los entornos y solo queda editar la tabla a mano.
     *
     * @return los nombres cuyas huellas se reescribieron
     */
    public List<String> repair() {
        Db db = Db.open(fuente);
        crearTablaDeControl(db);
        List<String> reparadas = new ArrayList<>();
        for (Migracion migracion : origen.leer()) {
            Row anotada = db.one("select huella from " + tabla + " where nombre = ?", migracion.nombre);
            if (anotada == null || migracion.huella.equals(String.valueOf(anotada.get("huella")))) {
                continue;
            }
            db.exec("update " + tabla + " set huella = ? where nombre = ?",
                    migracion.huella, migracion.nombre);
            reparadas.add(migracion.nombre);
        }
        return reparadas;
    }

    /**
     * Aplica el lote completo contra otro origen de datos —una base vacía y desechable, pensada
     * para integración continua— y devuelve el resultado en vez de lanzar.
     *
     * @param fuenteDesechable nombre de un origen ya registrado en {@link DataSources}
     */
    public Verificacion verify(String fuenteDesechable) {
        Migrations ensayo = new Migrations(fuenteDesechable, origen).table(tabla);
        try {
            return new Verificacion(true, ensayo.run(), null, null);
        } catch (RuntimeException fallo) {
            List<String> aplicadas = ensayo.applied();
            String rota = origen.leer().stream()
                    .map(Migracion::nombre)
                    .filter(nombre -> !aplicadas.contains(nombre))
                    .findFirst()
                    .orElse(null);
            return new Verificacion(false, aplicadas.size(), rota, fallo.getMessage());
        }
    }

    /** Lo que dejó {@link #verify(String)}: {@code migracion} y {@code fallo} solo si algo falló. */
    public record Verificacion(boolean correcta, int aplicadas, String migracion, String fallo) {

        public String resumen() {
            return correcta
                    ? aplicadas + " migraciones aplicadas sobre una base vacía, sin errores"
                    : "falló " + migracion + ": " + fallo;
        }
    }

    private void crearTablaDeControl(Db db) {
        db.exec("create table if not exists " + tabla + " ("
                + "nombre varchar(255) primary key, "
                + "huella varchar(64) not null, "
                + "aplicada_en bigint not null)");
    }

    private void aplicar(Migracion migracion) {
        List<String> sentencias = sentencias(migracion.sql);
        // Con Tx.run() la transacción sale siempre del origen por omisión, así que una migración
        // sobre otro origen acababa aplicándose contra la base equivocada.
        Tx.call(fuente, () -> {
            Db db = Db.open(fuente);
            for (int i = 0; i < sentencias.size(); i++) {
                try {
                    db.exec(sentencias.get(i));
                } catch (RuntimeException fallo) {
                    throw explicar(migracion.nombre, i + 1, fallo);
                }
            }
            db.exec("insert into " + tabla + " (nombre, huella, aplicada_en) values (?, ?, ?)",
                    migracion.nombre, migracion.huella, System.currentTimeMillis());
            return null;
        });
    }

    /**
     * El motor solo dice qué restricción se violó, no en qué archivo iba la sentencia. Eso lo
     * sabe el runner, y sin ello el fallo llega como un {@code SQLException} suelto.
     */
    static DataException explicar(String migracion, int numero, RuntimeException fallo) {
        SQLException motor = causaSql(fallo);
        String detalle = motor == null ? fallo.getMessage() : detalleDelMotor(motor);
        return new DataException("la migración " + migracion + " falló en la sentencia " + numero
                + ": " + detalle, fallo);
    }

    private static SQLException causaSql(Throwable fallo) {
        for (Throwable causa = fallo; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    private static String detalleDelMotor(SQLException fallo) {
        String mensaje = fallo.getMessage() == null ? "" : fallo.getMessage();
        if (CLAVE_FORANEA.equals(fallo.getSQLState())) {
            Matcher ausente = FILA_AUSENTE.matcher(mensaje);
            if (ausente.find()) {
                return "la fila `" + ausente.group(3) + "." + ausente.group(1) + "="
                        + ausente.group(2) + "` no existe";
            }
        }
        String estado = fallo.getSQLState();
        String primera = mensaje.lines().findFirst().orElse(mensaje).trim();
        return (estado == null ? "" : "[" + estado + "] ") + primera;
    }

    private static void comprobarHuella(Migracion migracion, String anotada) {
        if (!migracion.huella.equals(anotada)) {
            throw new DataException("la migración " + migracion.nombre + " ya se aplicó, pero su"
                    + " contenido cambió desde entonces.\n      aplicada  " + anotada
                    + "\n      ahora     " + migracion.huella
                    + "\n  Escribe una migración nueva en vez de editar esta; si el cambio no"
                    + " altera el esquema, repair() reescribe la huella.");
        }
    }

    /**
     * Parte por punto y coma. No se hace en el driver porque muchos no admiten varias sentencias
     * en un mismo {@code execute}, y una migración casi siempre son varias.
     *
     * <p>Un {@code split(";")} a secas parte por la mitad cualquier cuerpo de función
     * {@code $$ … $$}, literal o comentario que lleve dentro un punto y coma, y el motor responde
     * con un error de sintaxis que no señala la causa. Por eso se recorre el texto respetando
     * literales, identificadores entre comillas, comentarios y cadenas con etiqueta de dólar.
     */
    static List<String> sentencias(String sql) {
        List<String> partes = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char siguiente = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (c == '-' && siguiente == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && siguiente == '*') {
                i = finDelBloque(sql, i);
                actual.append(' ');
            } else if (c == '\'' || c == '"') {
                i = copiarCitado(sql, i, c, actual);
            } else if (c == ';') {
                anadir(partes, actual);
                i++;
            } else {
                String etiqueta = etiquetaDolar(sql, i);
                if (etiqueta == null) {
                    actual.append(c);
                    i++;
                } else {
                    i = copiarDolar(sql, i, etiqueta, actual);
                }
            }
        }
        anadir(partes, actual);
        return partes;
    }

    private static void anadir(List<String> partes, StringBuilder actual) {
        String sentencia = actual.toString().trim();
        actual.setLength(0);
        if (!sentencia.isEmpty()) {
            partes.add(sentencia);
        }
    }

    /** En PostgreSQL los comentarios de bloque anidan, así que no vale buscar el primer cierre. */
    private static int finDelBloque(String sql, int inicio) {
        int abiertos = 1;
        int i = inicio + 2;
        while (i < sql.length() && abiertos > 0) {
            if (sql.startsWith("/*", i)) {
                abiertos++;
                i += 2;
            } else if (sql.startsWith("*/", i)) {
                abiertos--;
                i += 2;
            } else {
                i++;
            }
        }
        return i;
    }

    private static int copiarCitado(String sql, int inicio, char comilla, StringBuilder destino) {
        destino.append(comilla);
        int i = inicio + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            destino.append(c);
            i++;
            if (c == comilla) {
                if (i < sql.length() && sql.charAt(i) == comilla) {
                    destino.append(comilla);
                    i++;
                    continue;
                }
                return i;
            }
        }
        return i;
    }

    /** {@code $$} o {@code $etiqueta$} en esta posición; {@code null} si no abre ninguna. */
    private static String etiquetaDolar(String sql, int inicio) {
        if (sql.charAt(inicio) != '$') {
            return null;
        }
        int i = inicio + 1;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        if (i >= sql.length() || sql.charAt(i) != '$') {
            return null;
        }
        if (i > inicio + 1 && Character.isDigit(sql.charAt(inicio + 1))) {
            return null;
        }
        return sql.substring(inicio, i + 1);
    }

    private static int copiarDolar(String sql, int inicio, String etiqueta, StringBuilder destino) {
        int cierre = sql.indexOf(etiqueta, inicio + etiqueta.length());
        int fin = cierre < 0 ? sql.length() : cierre + etiqueta.length();
        destino.append(sql, inicio, fin);
        return fin;
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
