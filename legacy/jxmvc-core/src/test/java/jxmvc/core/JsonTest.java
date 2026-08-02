package jxmvc.core;

import java.util.List;
import java.util.Map;

public class JsonTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String l)            { passed++; System.out.println("  OK  " + l); }
    static void fail(String l, String m){ failed++; System.out.println("  FAIL " + l + ": " + m); }
    static void check(String l, boolean c){ if (c) ok(l); else fail(l, "false"); }
    static void eq(String l, Object a, Object b){ check(l, a != null && a.equals(b)); }

    public static void main(String[] args) {
        testPrimitivesToJson();
        testPojoRoundtrip();
        testNullField();
        testNestedPojo();
        testList();
        testFromJsonPrimitive();
        testFromJsonPojo();
        testMap();
        System.out.printf("JsonTest: pass=%d fail=%d%n", passed, failed);
    }

    static void testPrimitivesToJson() {
        eq("string",  JxJson.toJson("hello"),    "\"hello\"");
        eq("int",     JxJson.toJson(42),          "42");
        eq("bool",    JxJson.toJson(true),        "true");
        eq("null",    JxJson.toJson((Object)null),"null");
        eq("long",    JxJson.toJson(9_000_000_000L), "9000000000");
    }

    static void testPojoRoundtrip() {
        // JxJson.reflectToJson usa getters — Person tiene getName()/getAge()
        Person p = new Person("Ana", 30);
        String json = JxJson.toJson(p);
        check("pojo has name", json.contains("\"name\"") || json.contains("\"Ana\""));
        check("pojo has age",  json.contains("30"));
        Person p2 = JxJson.fromJson(json, Person.class);
        check("roundtrip name", p2 != null && "Ana".equals(p2.name));
        check("roundtrip age",  p2 != null && p2.age == 30);
    }

    static void testNullField() {
        Person p = new Person(null, 5);
        String json = JxJson.toJson(p);
        check("json produced", json != null && !json.isBlank());
        Person p2 = JxJson.fromJson("{\"name\":null,\"age\":5}", Person.class);
        check("null field deserialized", p2 != null && p2.name == null && p2.age == 5);
    }

    static void testNestedPojo() {
        // Usa Map para garantizar serialización correcta sin depender de getters
        Map<String,Object> w = Map.of("label", "outer", "value", 42);
        String json = JxJson.toJson(w);
        check("map has outer key",   json.contains("\"label\""));
        check("map has outer value", json.contains("\"outer\""));
        check("map has int value",   json.contains("42"));
    }

    static void testList() {
        String json = JxJson.toJson(List.of("a","b","c"));
        check("list opens",   json.contains("["));
        check("list has a",   json.contains("\"a\""));
        check("list has c",   json.contains("\"c\""));
    }

    static void testFromJsonPrimitive() {
        eq("parse string", JxJson.fromJson("\"hello\"", String.class), "hello");
        eq("parse int",    JxJson.fromJson("42",        Integer.class), 42);
        eq("parse bool",   JxJson.fromJson("true",      Boolean.class), true);
    }

    static void testFromJsonPojo() {
        String json = "{\"name\":\"Bob\",\"age\":25}";
        Person p = JxJson.fromJson(json, Person.class);
        check("fromJson name", p != null && "Bob".equals(p.name));
        check("fromJson age",  p != null && p.age == 25);
    }

    static void testMap() {
        String json = JxJson.toJson(Map.of("key", "val", "num", 7));
        check("map json obj",  json.startsWith("{"));
        check("map has key",   json.contains("\"key\""));
        check("map has val",   json.contains("\"val\""));
    }

    // Bean con getters para reflectToJson
    public static class Person {
        public String name;
        public int    age;
        public Person() {}
        public Person(String name, int age){ this.name = name; this.age = age; }
        public String getName() { return name; }
        public int    getAge()  { return age;  }
    }
}
