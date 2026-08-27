package corvo.data;

import java.util.List;

final class RepositoryTests {

    private RepositoryTests() {
    }

    @Table("usuarios")
    record Usuario(@Id long id, String nombre, @Column("correo") String email) {
    }

    record LineaPedido(@Id(generated = false) String codigo, int cantidad) {
    }

    record SinId(String nombre) {
    }

    static void run() {
        Check.group("Repository");

        DataSources.clear();
        FakeDb.reset();
        DataSources.registerDefault(Pool.to(FakeDb.URL).validate(false).build());

        Repository<Usuario, Long> usuarios = new Repository<>(Usuario.class);

        Check.equal("toma el nombre de @Table", usuarios.table(), "usuarios");
        Check.equal("sin @Table lo deriva de la clase",
                new Repository<>(LineaPedido.class).table(), "linea_pedidos");

        FakeDb.willReturn(List.of(FakeDb.row("id", 1L, "nombre", "Ana", "correo", "ana@x.pe")));
        Usuario encontrado = usuarios.findById(1L);
        Check.equal("findById genera el SQL", FakeDb.lastQuery().sql(),
                "SELECT * FROM usuarios WHERE id = ?");
        Check.equal("y liga el identificador", FakeDb.lastQuery().params(), List.of(1L));
        Check.equal("@Column mapea el nombre de columna", encontrado.email(), "ana@x.pe");
        Check.equal("y el resto de campos", encontrado.nombre(), "Ana");

        FakeDb.willReturn(List.of());
        Check.equal("findById sin resultado devuelve null", usuarios.findById(9L), null);

        FakeDb.willReturn(List.of(FakeDb.row("id", 1L, "nombre", "Ana", "correo", "a@x")));
        Check.equal("findAll", usuarios.findAll().size(), 1);
        Check.equal("findAll consulta la tabla entera", FakeDb.lastQuery().sql(),
                "SELECT * FROM usuarios");

        FakeDb.willReturn(List.of(FakeDb.row("id", 2L, "nombre", "Luis", "correo", "l@x")));
        usuarios.findBy("nombre = ?", "Luis");
        Check.equal("findBy añade el filtro", FakeDb.lastQuery().sql(),
                "SELECT * FROM usuarios WHERE nombre = ?");

        usuarios.insert(new Usuario(0, "Ana", "ana@x.pe"));
        Check.equal("insert omite el id generado", FakeDb.lastQuery().sql(),
                "INSERT INTO usuarios (nombre, correo) VALUES (?, ?)");
        Check.equal("y liga los valores", FakeDb.lastQuery().params(), List.of("Ana", "ana@x.pe"));

        new Repository<LineaPedido, String>(LineaPedido.class).insert(new LineaPedido("A1", 3));
        Check.equal("con @Id(generated=false) el id sí se inserta", FakeDb.lastQuery().sql(),
                "INSERT INTO linea_pedidos (codigo, cantidad) VALUES (?, ?)");

        usuarios.update(new Usuario(5, "Ana María", "ana@x.pe"));
        Check.equal("update excluye el id del SET", FakeDb.lastQuery().sql(),
                "UPDATE usuarios SET nombre = ?, correo = ? WHERE id = ?");
        Check.equal("y lo usa como condición",
                FakeDb.lastQuery().params(), List.of("Ana María", "ana@x.pe", 5L));

        usuarios.deleteById(4L);
        Check.equal("deleteById", FakeDb.lastQuery().sql(), "DELETE FROM usuarios WHERE id = ?");

        FakeDb.willReturn(List.of(FakeDb.row("total", 12L)));
        Check.equal("count", usuarios.count(), 12L);

        FakeDb.willReturn(List.of(FakeDb.row("total", 1L)));
        Check.that("existsById es cierto si cuenta más de cero", usuarios.existsById(1L));
        FakeDb.willReturn(List.of(FakeDb.row("total", 0L)));
        Check.that("y falso si no", !usuarios.existsById(99L));

        FakeDb.willReturn(List.of(FakeDb.row("total", 30L)));
        FakeDb.willReturn(List.of(FakeDb.row("id", 1L, "nombre", "Ana", "correo", "a@x")));
        Page<Usuario> pagina = usuarios.findPage(2, 10);
        Check.equal("findPage cuenta y pagina", pagina.total(), 30L);
        Check.equal("con el desplazamiento correcto",
                FakeDb.lastQuery().params(), List.of(10, 10));
        Check.equal("y devuelve entidades", pagina.data().get(0).nombre(), "Ana");

        Check.raises("un tipo sin @Id falla al usar el identificador", DataException.class,
                () -> new Repository<SinId, String>(SinId.class).findById("x"));

        DataSources.clear();
    }
}
