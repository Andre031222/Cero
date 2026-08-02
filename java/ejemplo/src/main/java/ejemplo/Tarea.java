package ejemplo;

import lux.core.Length;
import lux.core.OneOf;
import lux.core.Required;
import lux.data.Column;
import lux.data.Id;
import lux.data.Table;

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
