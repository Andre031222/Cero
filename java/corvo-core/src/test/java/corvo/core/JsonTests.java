package corvo.core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class JsonTests {

    private JsonTests() {
    }

    record Persona(String nombre, int edad, boolean activo) {
    }

    record Equipo(String nombre, List<Persona> miembros) {
    }

    enum Estado { ALTA, BAJA }

    static final class Cuenta {
        private String titular;
        private double saldo;

        public String getTitular() {
            return titular;
        }

        public double getSaldo() {
            return saldo;
        }
    }

    static void run() {
        Check.group("json");

        escritura();
        lectura();
        vinculacion();
        errores();
    }

    private static void escritura() {
        Check.equal("null", Json.write(null), "null");
        Check.equal("cadena", Json.write("hola"), "\"hola\"");
        Check.equal("entero", Json.write(42), "42");
        Check.equal("decimal", Json.write(1.5), "1.5");
        Check.equal("booleano", Json.write(true), "true");
        Check.equal("lista", Json.write(List.of(1, 2, 3)), "[1,2,3]");
        Check.equal("mapa", Json.write(new LinkedHashMap<>(Map.of("a", 1))), "{\"a\":1}");
        Check.equal("record", Json.write(new Persona("Ana", 30, true)),
                "{\"nombre\":\"Ana\",\"edad\":30,\"activo\":true}");
        Check.equal("record anidado",
                Json.write(new Equipo("core", List.of(new Persona("Ana", 30, true)))),
                "{\"nombre\":\"core\",\"miembros\":[{\"nombre\":\"Ana\",\"edad\":30,\"activo\":true}]}");
        Check.equal("enum", Json.write(Estado.ALTA), "\"ALTA\"");
        Check.equal("optional con valor", Json.write(Optional.of("x")), "\"x\"");
        Check.equal("optional vacío", Json.write(Optional.empty()), "null");
        Check.equal("array nativo", Json.write(new int[]{1, 2}), "[1,2]");
        Check.equal("uuid", Json.write(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                "\"00000000-0000-0000-0000-000000000001\"");
        Check.equal("instante", Json.write(Instant.parse("2026-08-01T10:00:00Z")),
                "\"2026-08-01T10:00:00Z\"");
        Check.equal("fecha", Json.write(LocalDate.of(2026, 8, 1)), "\"2026-08-01\"");
        Check.equal("escapes", Json.write("a\"b\\c\nd\te"), "\"a\\\"b\\\\c\\nd\\te\"");
        Check.equal("control", Json.write("\u0001"), "\"\\u0001\"");
        Check.equal("acentos sin escapar", Json.write("añó"), "\"añó\"");
        Check.equal("NaN se serializa como null", Json.write(Double.NaN), "null");
        Check.equal("infinito se serializa como null", Json.write(Double.POSITIVE_INFINITY), "null");

        Cuenta cuenta = new Cuenta();
        cuenta.titular = "Ana";
        cuenta.saldo = 10.5;
        String bean = Json.write(cuenta);
        Check.that("bean usa getters", bean.contains("\"titular\":\"Ana\"") && bean.contains("\"saldo\":10.5"));
    }

    private static void lectura() {
        Check.equal("lee cadena", Json.read("\"hola\""), "hola");
        Check.equal("lee entero como long", Json.read("42"), 42L);
        Check.equal("lee decimal", Json.read("1.5"), 1.5);
        Check.equal("lee negativo", Json.read("-7"), -7L);
        Check.equal("lee exponente", Json.read("1e2"), 100.0);
        Check.equal("lee booleano", Json.read("true"), Boolean.TRUE);
        Check.equal("lee null", Json.read("null"), null);
        Check.equal("lee lista", Json.read("[1,2]"), List.of(1L, 2L));
        Check.equal("lee objeto", Json.read("{\"a\":1}"), Map.of("a", 1L));
        Check.equal("ignora espacios", Json.read("  {  \"a\" : 1 }  "), Map.of("a", 1L));
        Check.equal("lee escapes", Json.read("\"a\\nb\""), "a\nb");
        Check.equal("lee unicode", Json.read("\"\\u00f1\""), "ñ");
        Check.equal("lista vacía", Json.read("[]"), List.of());
        Check.equal("objeto vacío", Json.read("{}"), Map.of());
        Check.equal("anidado",
                Json.read("{\"a\":{\"b\":[1]}}"), Map.of("a", Map.of("b", List.of(1L))));
    }

    private static void vinculacion() {
        Persona persona = Json.read("{\"nombre\":\"Ana\",\"edad\":30,\"activo\":true}", Persona.class);
        Check.equal("record: nombre", persona.nombre(), "Ana");
        Check.equal("record: edad", persona.edad(), 30);
        Check.equal("record: activo", persona.activo(), true);

        Equipo equipo = Json.read(
                "{\"nombre\":\"core\",\"miembros\":[{\"nombre\":\"Ana\",\"edad\":30,\"activo\":true}]}",
                Equipo.class);
        Check.equal("record anidado: tamaño", equipo.miembros().size(), 1);
        Check.equal("record anidado: elemento", equipo.miembros().get(0).nombre(), "Ana");

        Check.equal("campo ausente queda a cero",
                Json.read("{\"nombre\":\"Ana\"}", Persona.class).edad(), 0);
        Check.equal("vincula a int", Json.bind(30L, int.class), 30);
        Check.equal("vincula texto a int", Json.bind("30", int.class), 30);
        Check.equal("vincula texto a boolean", Json.bind("true", boolean.class), true);
        Check.equal("vincula texto a enum", Json.bind("ALTA", Estado.class), Estado.ALTA);
        Check.equal("vincula texto a fecha", Json.bind("2026-08-01", LocalDate.class),
                LocalDate.of(2026, 8, 1));

        Cuenta cuenta = Json.read("{\"titular\":\"Ana\",\"saldo\":10.5}", Cuenta.class);
        Check.equal("bean: titular", cuenta.getTitular(), "Ana");
        Check.equal("bean: saldo", cuenta.getSaldo(), 10.5);

        Check.equal("ida y vuelta",
                Json.read(Json.write(new Persona("Ana", 30, true)), Persona.class),
                new Persona("Ana", 30, true));
    }

    private static void errores() {
        Check.raises("json truncado", IllegalArgumentException.class, () -> Json.read("{\"a\":"));
        Check.raises("cadena sin cerrar", IllegalArgumentException.class, () -> Json.read("\"abc"));
        Check.raises("contenido sobrante", IllegalArgumentException.class, () -> Json.read("{} {}"));
        Check.raises("documento vacío", IllegalArgumentException.class, () -> Json.read(""));
        Check.raises("clave sin comillas", IllegalArgumentException.class, () -> Json.read("{a:1}"));

        List<Object> ciclo = new ArrayList<>();
        ciclo.add(ciclo);
        Check.raises("ciclo al serializar", IllegalArgumentException.class, () -> Json.write(ciclo));
    }
}
