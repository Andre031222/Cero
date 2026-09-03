package ejemplo;

import cero.core.Length;
import cero.core.OneOf;
import cero.core.Required;
import cero.data.Column;
import cero.data.Id;
import cero.data.Table;

@Table("tareas")
public record Tarea(
        @Id long id,
        @Required @Length(min = 3, max = 120) String titulo,
        @OneOf({"baja", "media", "alta"}) String prioridad,
        @Column("hecha") boolean completada) {

    public static Tarea nueva(String titulo, String prioridad) {
        return new Tarea(0, titulo, prioridad, false);
    }

    public String etiqueta() {
        return completada ? "hecha" : "pendiente";
    }
}
