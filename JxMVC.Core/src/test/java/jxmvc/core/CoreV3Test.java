package jxmvc.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Tests completos v3.1.1 — sin framework externo.
 */
public class CoreV3Test {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        // DBRow
        testDbRowGetters();
        testDbRowTyped();
        testDbRowBigDecimal();
        testDbRowToJson();
        testDbRowOf();
        // DBRowSet
        testDbRowSet();
        // GenApi
        testGenApiJsonStr();
        testGenApiNested();
        testGenApiJsonArray();
        testGenApiJsonList();
        testGenApiJsonPaged();
        testGenApiError();
        testGenApiSpecialChars();
        testGenApiNullValues();
        testGenApiToJson();
        // JxValidation
        testValidationRequired();
        testValidationNotNull();
        testValidationEmail();
        testValidationRange();
        testValidationIn();
        testValidationPhone();
        testValidationDigits();
        testValidationSafe();
        testValidationStructured();
        // BaseSanitizer
        testSanitizerXss();
        testSanitizerBasic();
        // BaseDbResolver
        testDbResolverDefaults();
        // JxDB named params parser
        testNamedParams();

        System.out.println("\n=== Resultado: " + passed + " pasaron, " + failed + " fallaron ===");
        if (failed > 0) System.exit(1);
    }

    // ── DBRow ─────────────────────────────────────────────────────────────

    static void testDbRowGetters() {
        DBRow r = DBRow.of("id", 5, "nombre", "Ana", "activo", true, "saldo", 99.5, "ts", 1000L);
        eq("GetInt",    5,    r.GetInt("id"));
        eq("GetString","Ana", r.GetString("nombre"));
        eq("GetBool",  true,  r.GetBool("activo"));
        eq("GetDouble",99.5,  r.GetDouble("saldo"));
        eq("GetLong",  1000L, r.GetLong("ts"));
        eq("GetInt null",  0,    r.GetInt("noexiste"));
        eq("GetString null",null, r.GetString("noexiste"));
        eq("GetBool null", false, r.GetBool("noexiste"));
    }

    static void testDbRowTyped() {
        DBRow r = DBRow.of("n", "3.14", "entero", "42", "bool_str", "1");
        eq("GetDouble string", 3.14, r.GetDouble("n"));
        eq("GetInt string",    42,   r.GetInt("entero"));
        eq("GetBool '1'",      true, r.GetBool("bool_str"));
        eq("GetFloat",         3.14f, r.GetFloat("n"));
    }

    static void testDbRowBigDecimal() {
        DBRow r = DBRow.of("precio", "1234.56", "cero", null);
        eq("GetBigDecimal string", new BigDecimal("1234.56"), r.GetBigDecimal("precio"));
        eq("GetBigDecimal null",   BigDecimal.ZERO, r.GetBigDecimal("cero"));
    }

    static void testDbRowToJson() {
        DBRow r = DBRow.of("id", 1, "nombre", "Ana");
        String j = r.toJson();
        eq("toJson id",     true, j.contains("\"id\":1"));
        eq("toJson nombre", true, j.contains("\"nombre\":\"Ana\""));
    }

    static void testDbRowOf() {
        DBRow r = DBRow.of("a", 1, "b", "dos");
        eq("of size", 2, r.KeySet().size());
        eq("of get",  "dos", r.GetString("b"));
    }

    // ── DBRowSet ──────────────────────────────────────────────────────────

    static void testDbRowSet() {
        DBRowSet rs = new DBRowSet();
        eq("IsEmpty ini", true,  rs.IsEmpty());
        eq("Size ini",    0,     rs.Size());
        eq("First null",  null,  rs.First());
        rs.Add(DBRow.of("id", 1));
        rs.Add(DBRow.of("id", 2));
        eq("Size 2",      2,     rs.Size());
        eq("IsEmpty false",false,rs.IsEmpty());
        eq("First id",    1,     rs.First().GetInt("id"));
        eq("Get(1) id",   2,     rs.Get(1).GetInt("id"));
    }

    // ── GenApi ────────────────────────────────────────────────────────────

    static void testGenApiJsonStr() {
        String j = GenApi.JsonStr("ok", true, "id", 5, "nombre", "Ana");
        eq("bool",   true, j.contains("\"ok\":true"));
        eq("number", true, j.contains("\"id\":5"));
        eq("string", true, j.contains("\"nombre\":\"Ana\""));
    }

    static void testGenApiNested() {
        String j = GenApi.JsonStr("status","UP","pool",GenApi.nested("enabled",true,"size",10));
        eq("nested inline",     true,  j.contains("\"pool\":{\"enabled\":true,\"size\":10}"));
        eq("no comillas extra", false, j.contains("\"pool\":\"{"));
    }

    static void testGenApiJsonArray() {
        DBRowSet rs = new DBRowSet();
        rs.Add(DBRow.of("id",1,"n","Ana"));
        rs.Add(DBRow.of("id",2,"n","Pedro"));
        String j = GenApi.JsonArray(rs);
        eq("abre [",   '[', j.charAt(0));
        eq("cierra ]", ']', j.charAt(j.length()-1));
        eq("Ana",   true, j.contains("\"Ana\""));
        eq("Pedro", true, j.contains("\"Pedro\""));
        eq("vacio",  "[]", GenApi.JsonArray(new DBRowSet()));
        eq("null",   "[]", GenApi.JsonArray(null));
    }

    static void testGenApiJsonList() {
        String j = GenApi.JsonList(
            GenApi.nested("id", 1, "x", "a"),
            GenApi.nested("id", 2, "x", "b")
        );
        eq("JsonList abre",   '[', j.charAt(0));
        eq("JsonList id 1",   true, j.contains("\"id\":1"));
        eq("JsonList id 2",   true, j.contains("\"id\":2"));
    }

    static void testGenApiJsonPaged() {
        DBRowSet rs = new DBRowSet();
        rs.Add(DBRow.of("x", 1));
        JxPageResult p = JxPageResult.of(rs, 0, 20, 55L);
        String j = GenApi.JsonPaged(p);
        eq("total",  true, j.contains("\"total\":55"));
        eq("pages",  true, j.contains("\"pages\":3"));
        eq("data[]", true, j.contains("\"data\":["));
    }

    static void testGenApiError() {
        String e1 = GenApi.Error("no encontrado");
        eq("false",   true, e1.contains("\"success\":false"));
        eq("error",   true, e1.contains("\"error\":\"no encontrado\""));
        String e2 = GenApi.Error(404, "not found");
        eq("status",  true, e2.contains("\"status\":404"));
        String ok = GenApi.Ok("guardado");
        eq("Ok true", true, ok.contains("\"success\":true"));
    }

    static void testGenApiSpecialChars() {
        String j = GenApi.JsonStr("msg", "hola \"mundo\"\nnueva");
        eq("escape \"", true, j.contains("\\\"mundo\\\""));
        eq("escape \\n",true, j.contains("\\n"));
    }

    static void testGenApiNullValues() {
        String j = GenApi.JsonStr("a", null, "b", "ok");
        eq("null",  true, j.contains("\"a\":null"));
        eq("ok",    true, j.contains("\"b\":\"ok\""));
    }

    static void testGenApiToJson() {
        eq("toJson null",   "null",  GenApi.toJson(null));
        eq("toJson int",    "42",    GenApi.toJson(42));
        eq("toJson bool",   "true",  GenApi.toJson(true));
        eq("toJson string", "\"hi\"",GenApi.toJson("hi"));
    }

    // ── JxValidation ─────────────────────────────────────────────────────

    static class UserDto {
        @JxValidation.JxRequired public String nombre;
        @JxValidation.JxRequired @JxValidation.JxEmail public String email;
        @JxValidation.JxRange(min=0, max=120) public int edad;
        @JxValidation.JxIn({"admin","user","guest"}) public String rol;
        @JxValidation.JxPhone public String telefono;
        @JxValidation.JxDigits(8) public String dni;
        @JxValidation.JxSafe public String descripcion;
        @JxValidation.JxNotNull public String noNulo;
    }

    static void testValidationRequired() {
        UserDto dto = new UserDto();
        dto.nombre = "";
        dto.email  = "x@x.com";
        dto.noNulo = "ok";
        var errs = JxValidation.checkMap(dto);
        eq("required vacio", true, errs.containsKey("nombre"));
        eq("email ok",       false, errs.containsKey("email"));
    }

    static void testValidationNotNull() {
        UserDto dto = new UserDto();
        dto.nombre = "Ana";
        dto.email  = "ana@x.com";
        dto.noNulo = null;
        var errs = JxValidation.checkMap(dto);
        eq("notNull null", true, errs.containsKey("noNulo"));
        dto.noNulo = "";
        errs = JxValidation.checkMap(dto);
        eq("notNull empty ok", false, errs.containsKey("noNulo"));
    }

    static void testValidationEmail() {
        UserDto dto = new UserDto();
        dto.nombre = "x"; dto.email = "mal-email"; dto.noNulo = "ok";
        var errs = JxValidation.checkMap(dto);
        eq("email invalido", true, errs.containsKey("email"));
        dto.email = "correcto@dominio.com";
        errs = JxValidation.checkMap(dto);
        eq("email valido", false, errs.containsKey("email"));
    }

    static void testValidationRange() {
        UserDto dto = new UserDto();
        dto.nombre = "x"; dto.email = "x@x.com"; dto.noNulo = "ok";
        dto.edad = 200;
        var errs = JxValidation.checkMap(dto);
        eq("range max", true, errs.containsKey("edad"));
        dto.edad = 25;
        errs = JxValidation.checkMap(dto);
        eq("range ok", false, errs.containsKey("edad"));
    }

    static void testValidationIn() {
        UserDto dto = new UserDto();
        dto.nombre = "x"; dto.email = "x@x.com"; dto.noNulo = "ok";
        dto.rol = "superadmin";
        var errs = JxValidation.checkMap(dto);
        eq("in invalido", true, errs.containsKey("rol"));
        dto.rol = "admin";
        errs = JxValidation.checkMap(dto);
        eq("in valido", false, errs.containsKey("rol"));
    }

    static void testValidationPhone() {
        UserDto dto = new UserDto();
        dto.nombre = "x"; dto.email = "x@x.com"; dto.noNulo = "ok";
        dto.telefono = "abc";
        var errs = JxValidation.checkMap(dto);
        eq("phone invalido", true, errs.containsKey("telefono"));
        dto.telefono = "+51 999 999 999";
        errs = JxValidation.checkMap(dto);
        eq("phone valido", false, errs.containsKey("telefono"));
    }

    static void testValidationDigits() {
        UserDto dto = new UserDto();
        dto.nombre = "x"; dto.email = "x@x.com"; dto.noNulo = "ok";
        dto.dni = "1234567";
        var errs = JxValidation.checkMap(dto);
        eq("digits 7 invalido", true, errs.containsKey("dni"));
        dto.dni = "12345678";
        errs = JxValidation.checkMap(dto);
        eq("digits 8 valido", false, errs.containsKey("dni"));
        dto.dni = "1234567a";
        errs = JxValidation.checkMap(dto);
        eq("digits letra invalido", true, errs.containsKey("dni"));
    }

    static void testValidationSafe() {
        UserDto dto = new UserDto();
        dto.nombre = "x"; dto.email = "x@x.com"; dto.noNulo = "ok";
        dto.descripcion = "hola'; DROP TABLE users;--";
        var errs = JxValidation.checkMap(dto);
        eq("safe injection",  true, errs.containsKey("descripcion"));
        dto.descripcion = "texto normal sin caracteres peligrosos";
        errs = JxValidation.checkMap(dto);
        eq("safe ok", false, errs.containsKey("descripcion"));
    }

    static void testValidationStructured() {
        UserDto dto = new UserDto();
        dto.noNulo = "ok";
        // Todo vacío — múltiples errores
        var errs = JxValidation.checkMap(dto);
        eq("multiples errores", true, errs.size() >= 2);
        // validate() lanza JxException con JSON
        try {
            JxValidation.validate(dto);
            eq("validate no lanzó excepción", false, true);
        } catch (JxException e) {
            eq("validate status 422", 422, e.getStatus());
            eq("validate json errors", true, e.getMessage().contains("\"errors\""));
        }
    }

    // ── BaseSanitizer ─────────────────────────────────────────────────────

    static void testSanitizerXss() {
        String xss1 = BaseSanitizer.clean("<script>alert('xss')</script>hola");
        eq("script removido",   false, xss1.contains("<script>"));
        eq("texto preservado",  true,  xss1.contains("hola"));

        String xss2 = BaseSanitizer.clean("<img onerror=alert(1) src=x>");
        eq("onerror removido",  false, xss2.contains("onerror"));

        String xss3 = BaseSanitizer.clean("javascript:alert(1)");
        eq("js protocol",       false, xss3.contains("javascript:"));

        String xss4 = BaseSanitizer.clean("<iframe src='evil.com'></iframe>texto");
        eq("iframe removido",   false, xss4.contains("<iframe"));
        eq("texto ok",          true,  xss4.contains("texto"));
    }

    static void testSanitizerBasic() {
        eq("null safe",    null,  BaseSanitizer.clean(null));
        eq("clean normal", "hola mundo", BaseSanitizer.clean("hola mundo"));
        eq("cleanBasic",   "test", BaseSanitizer.cleanBasic("<test>"));
    }

    // ── BaseDbResolver ────────────────────────────────────────────────────

    static void testDbResolverDefaults() {
        // Sin application.properties en test classpath, deben devolver defaults
        String url  = BaseDbResolver.url();
        String user = BaseDbResolver.user();
        eq("url no null",  true, url  != null && !url.isBlank());
        eq("user no null", true, user != null && !user.isBlank());
        eq("pool false",   false, BaseDbResolver.poolEnabled());
        eq("pool size 10", 10,    BaseDbResolver.poolSize());
    }

    // ── JxDB named params ─────────────────────────────────────────────────

    static void testNamedParams() {
        // Probar el parser de named params sin conexión real — via reflexión
        try {
            var m = JxDB.class.getDeclaredMethod("parseNamed", String.class, Map.class);
            m.setAccessible(true);
            Object nq = m.invoke(null,
                "SELECT * FROM users WHERE name = :name AND age > :age",
                Map.of("name", "Ana", "age", 18));
            var sqlField = nq.getClass().getDeclaredField("sql");
            var paramsField = nq.getClass().getDeclaredField("params");
            sqlField.setAccessible(true);
            paramsField.setAccessible(true);
            String sql = (String) sqlField.get(nq);
            Object[] params = (Object[]) paramsField.get(nq);
            eq("named sql ?",     true, sql.contains("?") && !sql.contains(":name"));
            eq("named params 2",  2,    params.length);
        } catch (Exception e) {
            eq("named params reflex " + e.getMessage(), true, false);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) { passed++; System.out.println("  ✓ " + name); }
        else    { failed++; System.out.println("  ✗ " + name
                    + " — esperado: [" + expected + "] obtenido: [" + actual + "]"); }
    }
}
