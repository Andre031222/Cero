package cero.test;

final class Check {

    private static int passed;
    private static int failed;

    private Check() {
    }

    static void group(String name) {
        System.out.println();
        System.out.println("── " + name);
    }

    static void that(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  OK  " + name);
        } else {
            failed++;
            System.out.println("  XX  " + name);
        }
    }

    static void equal(String name, Object actual, Object expected) {
        that(name + "   esperado=<" + expected + "> obtenido=<" + actual + ">",
                expected == null ? actual == null : expected.equals(actual));
    }

    /** La aserción debe fallar, y el mensaje del fallo debe explicar por qué. */
    static void fails(String name, Runnable action, String... fragments) {
        try {
            action.run();
            failed++;
            System.out.println("  XX  " + name + "   no falló");
        } catch (AssertionError error) {
            String message = String.valueOf(error.getMessage());
            for (String fragment : fragments) {
                if (!message.contains(fragment)) {
                    failed++;
                    System.out.println("  XX  " + name + "   el mensaje no menciona <" + fragment
                            + ">: " + message);
                    return;
                }
            }
            passed++;
            System.out.println("  OK  " + name);
        }
    }

    static int failures() {
        return failed;
    }

    static void report() {
        System.out.println();
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("  TOTAL  pass=%d  fail=%d%n", passed, failed);
    }
}
