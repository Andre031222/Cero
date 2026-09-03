package ejemplo;

import cero.core.Service;
import cero.data.Db;
import cero.data.Repository;
import cero.data.Row;

import java.util.List;

@Service
public class Tareas extends Repository<Tarea, Long> {

    public Tareas() {
        super(Tarea.class);
    }

    public List<Tarea> pendientes() {
        return findBy("hecha = ?", false);
    }

    public long cuantasPendientes() {
        return countBy("hecha = ?", false);
    }

    public void completar(long id) {
        db().update("tareas", Row.of("hecha", true), "id = ?", id);
    }

    public static void crearEsquema() {
        Db.open().exec("""
                CREATE TABLE IF NOT EXISTS tareas (
                    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
                    titulo    VARCHAR(120) NOT NULL,
                    prioridad VARCHAR(10)  NOT NULL,
                    hecha     BOOLEAN      NOT NULL DEFAULT FALSE
                )""");
    }
}
