package cero.data;

import cero.core.Json;
import cero.http.SessionStore;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Sesiones en una tabla, para que sobrevivan al despliegue y las compartan varias instancias.
 *
 * <p>El almacén por defecto vive en el montón del proceso, y eso tiene dos consecuencias que solo
 * se notan en producción: <b>cada despliegue echa a todo el mundo</b>, y no se puede correr más de
 * una instancia — con lo que no hay reinicio sin corte ni escalado horizontal.
 *
 * <pre>{@code
 * ServerOptions.builder()
 *     .sessionStore(JdbcSessions.of("cero_sesiones").createTable())
 *     .build();
 * }</pre>
 *
 * <p>Los valores se guardan como JSON, así que tienen que ser cosas que el {@link Json} de
 * Cero sepa escribir y volver a leer: texto, números, booleanos, listas y mapas. Al releerlos
 * vuelven como esos tipos, no como la clase original — guardar un objeto propio y esperar
 * recuperarlo tal cual no funciona. Es la misma regla de cualquier sesión distribuida.
 *
 * <p>Escribe con {@code UPDATE} condicional, así que dos peticiones simultáneas sobre la misma
 * sesión no se pisan: la que pierde reintenta sobre lo último guardado.
 */
public final class JdbcSessions implements SessionStore {

    /**
     * Reintentos del UPDATE condicional. Con cinco no bastaba: veinte escritores sobre la misma
     * fila agotaban los intentos y se perdían escrituras. Con espera creciente entre intentos,
     * los que pierden se reparten en el tiempo en vez de volver todos a la vez.
     */
    private static final int REINTENTOS = 24;

    private final String fuente;
    private final String tabla;

    private JdbcSessions(String fuente, String tabla) {
        this.fuente = fuente;
        this.tabla = tabla;
    }

    public static JdbcSessions of(String tabla) {
        return new JdbcSessions(DataSources.DEFAULT, tabla);
    }

    public static JdbcSessions of(String fuente, String tabla) {
        return new JdbcSessions(fuente, tabla);
    }

    /**
     * Crea la tabla si no está. Cómodo para arrancar; en un proyecto con migraciones, mejor que
     * la tabla salga de ahí y esto no se llame.
     */
    public JdbcSessions createTable() {
        db().exec("create table if not exists " + tabla + " ("
                + "id varchar(64) primary key, "
                + "datos varchar(65535) not null, "
                + "creada bigint not null, "
                + "ultimo_acceso bigint not null, "
                + "version bigint not null)");
        return this;
    }

    @Override
    public Datos load(String id) {
        Row fila = db().one("select datos, creada, ultimo_acceso from " + tabla + " where id = ?", id);
        return fila == null ? null : aDatos(fila);
    }

    @Override
    public void save(String id, Datos datos) {
        String json = Json.write(datos.valores());
        int tocadas = db().exec("update " + tabla
                        + " set datos = ?, creada = ?, ultimo_acceso = ?, version = version + 1"
                        + " where id = ?",
                json, datos.creada(), datos.ultimoAcceso(), id);
        if (tocadas == 0) {
            db().exec("insert into " + tabla + " (id, datos, creada, ultimo_acceso, version)"
                    + " values (?, ?, ?, ?, 0)", id, json, datos.creada(), datos.ultimoAcceso());
        }
    }

    /**
     * Lee, aplica el cambio y escribe solo si nadie tocó la fila mientras tanto. Si la tocaron,
     * se vuelve a leer y se reintenta: así la escritura de una petición no borra la de otra.
     */
    @Override
    public void update(String id, UnaryOperator<Datos> cambio) {
        for (int intento = 0; intento < REINTENTOS; intento++) {
            Row fila = db().one(
                    "select datos, creada, ultimo_acceso, version from " + tabla + " where id = ?", id);

            if (fila == null) {
                Datos nuevo = cambio.apply(null);
                if (nuevo == null) {
                    return;
                }
                db().exec("insert into " + tabla + " (id, datos, creada, ultimo_acceso, version)"
                                + " values (?, ?, ?, ?, 0)",
                        id, Json.write(nuevo.valores()), nuevo.creada(), nuevo.ultimoAcceso());
                return;
            }

            Datos nuevo = cambio.apply(aDatos(fila));
            if (nuevo == null) {
                return;
            }
            long version = ((Number) fila.get("version")).longValue();
            int tocadas = db().exec("update " + tabla
                            + " set datos = ?, creada = ?, ultimo_acceso = ?, version = ?"
                            + " where id = ? and version = ?",
                    Json.write(nuevo.valores()), nuevo.creada(), nuevo.ultimoAcceso(),
                    version + 1, id, version);
            if (tocadas > 0) {
                return;
            }
            esperarUnPoco(intento);
        }
        throw new DataException("no se pudo guardar la sesión " + id + " tras "
                + REINTENTOS + " intentos: hay demasiada contención sobre la misma sesión");
    }

    @Override
    public void remove(String id) {
        db().exec("delete from " + tabla + " where id = ?", id);
    }

    @Override
    public void sweep(long timeoutMillis) {
        db().exec("delete from " + tabla + " where ultimo_acceso < ?",
                System.currentTimeMillis() - timeoutMillis);
    }

    @Override
    public int size() {
        Row fila = db().one("select count(*) as total from " + tabla);
        return fila == null ? 0 : ((Number) fila.get("total")).intValue();
    }

    /** Espera creciente con un poco de azar, para que los que reintentan no vuelvan a la vez. */
    private static void esperarUnPoco(int intento) {
        long millis = Math.min(1L << Math.min(intento, 5), 40)
                + java.util.concurrent.ThreadLocalRandom.current().nextLong(4);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException cortado) {
            Thread.currentThread().interrupt();
            throw new DataException("interrumpido al guardar la sesión", cortado);
        }
    }

    private Db db() {
        return Db.open(fuente);
    }

    @SuppressWarnings("unchecked")
    private static Datos aDatos(Row fila) {
        Object leido = Json.read(String.valueOf(fila.get("datos")));
        Map<String, Object> valores = leido instanceof Map<?, ?> mapa
                ? new HashMap<>((Map<String, Object>) mapa)
                : new HashMap<>();
        return new Datos(valores,
                ((Number) fila.get("creada")).longValue(),
                ((Number) fila.get("ultimo_acceso")).longValue());
    }
}
