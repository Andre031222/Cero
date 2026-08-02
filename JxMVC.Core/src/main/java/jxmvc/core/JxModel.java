/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

/**
 * Clase base para modelos con patron Active Record.
 *
 * <p>La filosofia: la Base de Datos ya define el esquema — no hay que
 * re-declarar los campos en Java. Se usa {@link DBRow#GetString(String)},
 * {@link DBRow#GetInt(String)}, etc. para acceso dinamico por nombre de columna.
 *
 * <pre>
 *   public class Producto extends JxModel {
 *       private static final String T = "productos";
 *
 *       public static DBRowSet todos() {
 *           try (JxDB db = db()) { return db.query("SELECT * FROM " + T); }
 *           catch (Exception e) { return new DBRowSet(); }
 *       }
 *
 *       public static DBRow porId(Object id) {
 *           try (JxDB db = db()) {
 *               return db.queryRow("SELECT * FROM " + T + " WHERE id = ?", id);
 *           } catch (Exception e) { return null; }
 *       }
 *
 *       public static long guardar(DBRow datos) {
 *           try (JxDB db = db()) { return db.insert(T, datos); }
 *           catch (Exception e) { return -1; }
 *       }
 *
 *       public static int eliminar(Object id) {
 *           try (JxDB db = db()) {
 *               return db.exec("DELETE FROM " + T + " WHERE id = ?", id);
 *           } catch (Exception e) { return 0; }
 *       }
 *   }
 *
 *   // En el controlador — sin @JxInject, sin @JxService, sin Repository separado:
 *   DBRow p  = Producto.porId(42);
 *   String n = p.GetString("nombre");
 *   double v = p.GetDouble("precio");
 * </pre>
 */
public abstract class JxModel {

    /**
     * Obtiene una conexion JDBC del pool configurado en {@code application.properties}.
     * Usar siempre con try-with-resources.
     */
    protected static JxDB db() {
        return new JxDB();
    }
}
