package jxmvc.core;

public class CacheTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String label) { passed++; System.out.println("  OK  " + label); }
    static void fail(String label, String msg) { failed++; System.out.println("  FAIL " + label + ": " + msg); }
    static void check(String label, boolean cond) { if (cond) ok(label); else fail(label, "false"); }

    public static void main(String[] args) throws Exception {
        testPutFetch();
        testTtlExpiry();
        testEvict();
        testEvictAll();
        testComputeIfAbsent();
        testNoExpiry();
        testStampede();
        testMaxEntries();
        System.out.printf("CacheTest: pass=%d fail=%d%n", passed, failed);
    }

    static void testPutFetch() {
        JxCache c = JxCache.get("test-put");
        c.put("k1", "hello", 60);
        check("put/fetch string", "hello".equals(c.fetch("k1", String.class)));
        c.put("k2", 42, 60);
        check("put/fetch int", Integer.valueOf(42).equals(c.fetch("k2", Integer.class)));
        check("fetch null for missing", c.fetch("missing", String.class) == null);
    }

    static void testTtlExpiry() throws Exception {
        JxCache c = JxCache.get("test-ttl");
        c.put("exp", "value", 1);
        check("before expiry", c.has("exp"));
        Thread.sleep(1100);
        check("after expiry", !c.has("exp"));
        check("fetch null after expiry", c.fetch("exp", String.class) == null);
    }

    static void testEvict() {
        JxCache c = JxCache.get("test-evict");
        c.put("a", "A", 60).put("b", "B", 60);
        c.evict("a");
        check("evict removes key", c.fetch("a", String.class) == null);
        check("evict keeps other", "B".equals(c.fetch("b", String.class)));
    }

    static void testEvictAll() {
        JxCache c = JxCache.get("test-evictall");
        c.put("x", 1, 60).put("y", 2, 60);
        JxCache.evictAll("test-evictall");
        check("evictAll clears cache", c.fetch("x", Integer.class) == null);
        check("evictAll clears all keys", c.fetch("y", Integer.class) == null);
    }

    static void testComputeIfAbsent() {
        JxCache c = JxCache.get("test-compute");
        int[] calls = {0};
        String v1 = c.computeIfAbsent("key", 60, () -> { calls[0]++; return "computed"; });
        String v2 = c.computeIfAbsent("key", 60, () -> { calls[0]++; return "computed2"; });
        check("compute returns value", "computed".equals(v1));
        check("second compute is cache hit", "computed".equals(v2));
        check("loader called once", calls[0] == 1);
    }

    static void testNoExpiry() {
        JxCache c = JxCache.get("test-noexp");
        c.put("forever", "yes");
        check("no-expiry entry present", "yes".equals(c.fetch("forever", String.class)));
    }

    static void testStampede() throws Exception {
        JxCache c = JxCache.get("test-stampede");
        var calls = new java.util.concurrent.atomic.AtomicInteger(0);
        var start = new java.util.concurrent.CountDownLatch(1);
        var done  = new java.util.concurrent.CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    c.computeIfAbsent("hot", 60, () -> {
                        calls.incrementAndGet();
                        Thread.sleep(50);
                        return "value";
                    });
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        check("stampede: loader ejecutado una sola vez con 20 threads", calls.get() == 1);
    }

    static void testMaxEntries() {
        JxCache c = JxCache.get("test-maxentries");
        for (int i = 0; i < 10_050; i++) c.put("k" + i, i, 600);
        check("la caché respeta el tope de entradas", c.size() <= 10_000);
    }
}
