package jxmvc.core;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PoolTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String label) { passed++; System.out.println("  OK  " + label); }
    static void fail(String label, String msg) { failed++; System.out.println("  FAIL " + label + ": " + msg); }
    static void check(String label, boolean cond) { if (cond) ok(label); else fail(label, "false"); }

    static final String URL = "jdbc:postgresql://stub/testdb";

    public static void main(String[] args) throws Exception {
        testBorrowRelease();
        testDeadIdleDrained();
        testReleaseInvalidCloses();
        testTotalNeverExceedsMax();
        testLazyValidation();
        System.out.printf("PoolTest: pass=%d fail=%d%n", passed, failed);
    }

    static JxPool pool(int maxSize, int validationInterval) {
        org.postgresql.Driver.reset();
        return new JxPool(URL, "u", "p", maxSize, 2, validationInterval);
    }

    static void testBorrowRelease() throws Exception {
        JxPool p = pool(5, 30);
        Connection c = p.borrow();
        check("borrow entrega conexión", c != null);
        check("active cuenta la prestada", p.active() == 1);
        p.release(c);
        check("release la devuelve a idle", p.active() == 0);
        check("total acotado tras ciclo", p.total() <= 5);
        p.shutdown();
    }

    static void testDeadIdleDrained() throws Exception {
        JxPool p = pool(5, 0);
        Connection c1 = p.borrow();
        Connection c2 = p.borrow();
        p.release(c1);
        p.release(c2);
        int before = p.total();
        c1.close();
        c2.close();
        Connection c3 = p.borrow();
        check("borrow drena muertas y entrega viva", c3 != null && !c3.isClosed());
        check("total exacto tras drenar", p.total() == before - 1);
        check("muertas realmente cerradas", org.postgresql.Driver.closed.contains(c1)
                                          && org.postgresql.Driver.closed.contains(c2));
        p.release(c3);
        p.shutdown();
    }

    static void testReleaseInvalidCloses() throws Exception {
        JxPool p = pool(5, 0);
        Connection c = p.borrow();
        int before = p.total();
        c.close();
        p.release(c);
        check("release de inválida decrementa total", p.total() == before - 1);
        p.shutdown();
    }

    static void testTotalNeverExceedsMax() throws Exception {
        int max = 5;
        JxPool p = pool(max, 30);
        int threads = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        AtomicBoolean exceeded = new AtomicBoolean(false);
        AtomicInteger errors   = new AtomicInteger(0);

        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 20; j++) {
                        Connection c = p.borrow();
                        if (p.total() > max) exceeded.set(true);
                        p.release(c);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            t.start();
            ts.add(t);
        }
        start.countDown();
        done.await();
        check("total nunca excede maxSize bajo concurrencia", !exceeded.get());
        check("sin errores en borrow/release concurrente", errors.get() == 0);
        check("total final exacto", p.total() <= max && p.total() == p.available());
        p.shutdown();
    }

    static void testLazyValidation() throws Exception {
        JxPool lazy = pool(3, 3600);
        int after = org.postgresql.Driver.validationCalls.get();
        for (int i = 0; i < 10; i++) {
            Connection c = lazy.borrow();
            lazy.release(c);
        }
        check("validación lazy no consulta la BD dentro del intervalo",
                org.postgresql.Driver.validationCalls.get() == after);
        lazy.shutdown();

        JxPool eager = pool(3, 0);
        int base = org.postgresql.Driver.validationCalls.get();
        Connection c = eager.borrow();
        eager.release(c);
        check("validationInterval=0 valida siempre",
                org.postgresql.Driver.validationCalls.get() > base);
        eager.shutdown();
    }
}
