package corvo.view;

final class Check {

    private static int passed;
    private static int failed;
    private static String group = "";

    private Check() {
    }

    static void group(String name) {
        group = name;
        System.out.println();
        System.out.println("── " + name);
    }

    static void that(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  OK  " + name);
        } else {
            failed++;
            System.out.println("  XX  " + name + "   [" + group + "]");
        }
    }

    static void equal(String name, Object actual, Object expected) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            passed++;
            System.out.println("  OK  " + name);
        } else {
            failed++;
            System.out.println("  XX  " + name + "   esperado=<" + expected + "> obtenido=<" + actual + ">");
        }
    }

    static void raises(String name, Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
            failed++;
            System.out.println("  XX  " + name + "   no lanzó " + type.getSimpleName());
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                passed++;
                System.out.println("  OK  " + name);
            } else {
                failed++;
                System.out.println("  XX  " + name + "   lanzó " + thrown.getClass().getSimpleName());
            }
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
