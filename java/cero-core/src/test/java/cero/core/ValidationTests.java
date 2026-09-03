package cero.core;

import java.util.List;
import java.util.Map;

final class ValidationTests {

    private ValidationTests() {
    }

    record Registro(
            @Required String nombre,
            @Required @Email String correo,
            @Length(min = 8, max = 64) String clave,
            @Range(min = 18, max = 120) int edad,
            @OneOf({"admin", "editor", "lector"}) String rol,
            @Match("\\d{8}") String dni) {
    }

    record Minimo(@Required String nombre) {
    }

    static final class Formulario {
        @Required
        String titulo;
        @Length(max = 10)
        String resumen;
    }

    static final class Ruc implements Rule<String> {
        @Override
        public boolean test(String value) {
            return value != null && value.length() == 11 && value.startsWith("20");
        }

        @Override
        public String message() {
            return "no es un RUC válido";
        }
    }

    record Empresa(@Satisfies(Ruc.class) String ruc) {
    }

    static void run() {
        Check.group("validación");

        valido();
        invalido();
        mensajes();
        objetos();
    }

    private static void valido() {
        Registro completo = new Registro("Ana", "ana@unap.edu.pe", "unaclave123", 30, "admin", "12345678");
        Check.that("un registro correcto pasa", Validation.valid(completo));
        Check.equal("y no reporta problemas", Validation.problems(completo).size(), 0);
        Check.that("check no lanza", noLanza(() -> Validation.check(completo)));
        Check.that("un objeto null se considera válido", Validation.valid(null));
    }

    private static void invalido() {
        Registro roto = new Registro("", "no-es-correo", "corta", 5, "otro", "abc");
        Map<String, String> problemas = Validation.problems(roto);

        Check.equal("detecta los seis campos", problemas.size(), 6);
        Check.that("@Required sobre cadena vacía", problemas.containsKey("nombre"));
        Check.that("@Email", problemas.containsKey("correo"));
        Check.that("@Length", problemas.containsKey("clave"));
        Check.that("@Range", problemas.containsKey("edad"));
        Check.that("@OneOf", problemas.containsKey("rol"));
        Check.that("@Match", problemas.containsKey("dni"));

        Check.raises("check lanza ValidationException", ValidationException.class,
                () -> Validation.check(roto));

        try {
            Validation.check(roto);
        } catch (ValidationException error) {
            Check.equal("la excepción es un 422", error.status(), 422);
            Check.equal("y lleva el mapa de campos", error.problems().size(), 6);
        }

        Check.that("@Required sobre null", Validation.problems(new Minimo(null)).containsKey("nombre"));
        Check.that("@Required sobre espacios", Validation.problems(new Minimo("   ")).containsKey("nombre"));
        Check.that("un valor null omite el resto de reglas",
                Validation.problems(new Registro("Ana", "ana@x.pe", null, 30, "admin", "12345678"))
                        .isEmpty());
    }

    private static void mensajes() {
        Map<String, String> problemas = Validation.problems(
                new Registro("", "x", "abc", 5, "otro", "1"));

        Check.equal("mensaje de @Required", problemas.get("nombre"), "es obligatorio");
        Check.equal("mensaje de @Email", problemas.get("correo"), "no es un correo válido");
        Check.equal("mensaje de @Length con rango", problemas.get("clave"),
                "debe tener entre 8 y 64 caracteres");
        Check.equal("mensaje de @Range con rango", problemas.get("edad"), "debe estar entre 18 y 120");
        Check.that("mensaje de @OneOf enumera", problemas.get("rol").contains("admin, editor, lector"));

        Formulario largo = new Formulario();
        largo.titulo = "ok";
        largo.resumen = "excede el máximo permitido";
        Check.equal("mensaje de @Length solo con máximo",
                Validation.problems(largo).get("resumen"), "no puede pasar de 10 caracteres");
    }

    private static void objetos() {
        Formulario vacio = new Formulario();
        Check.that("valida también clases con campos", Validation.problems(vacio).containsKey("titulo"));

        Formulario completo = new Formulario();
        completo.titulo = "Informe";
        completo.resumen = "corto";
        Check.that("y las acepta cuando cumplen", Validation.valid(completo));

        Check.that("@Satisfies usa la regla propia",
                Validation.problems(new Empresa("20123456789")).isEmpty());
        Check.equal("y su mensaje cuando falla",
                Validation.problems(new Empresa("10")).get("ruc"), "no es un RUC válido");

        Check.equal("las listas vacías cuentan como ausentes",
                Validation.problems(new ConLista(List.of())).size(), 1);
        Check.equal("y las que traen elementos pasan",
                Validation.problems(new ConLista(List.of("a"))).size(), 0);
    }

    record ConLista(@Required List<String> etiquetas) {
    }

    private static boolean noLanza(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
