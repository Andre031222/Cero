package jxmvc.core;

import jakarta.websocket.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class WebSocketTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String label) { passed++; System.out.println("  OK  " + label); }
    static void fail(String label, String msg) { failed++; System.out.println("  FAIL " + label + ": " + msg); }
    static void check(String label, boolean cond) { if (cond) ok(label); else fail(label, "false"); }

    static class TestSocket extends JxWebSocket {
        void broadcastTo(String room, String msg) { broadcast(room, msg); }
        void join(String room, Session s)         { joinRoom(room, s); }
    }

    public static void main(String[] args) throws Exception {
        testOpenCloseLifecycle();
        testErrorCleansClosedSession();
        testConcurrentBroadcast();
        System.out.printf("WebSocketTest: pass=%d fail=%d%n", passed, failed);
    }

    static void testOpenCloseLifecycle() {
        TestSocket socket = new TestSocket();
        int base = JxWebSocket.totalConnections();
        var st = new TestStubs.SessionState();
        Session s = TestStubs.session(st);
        socket._onOpen(s);
        check("onOpen registra la sesión", JxWebSocket.totalConnections() == base + 1);
        socket._onClose(s);
        check("onClose limpia la sesión", JxWebSocket.totalConnections() == base);
    }

    static void testErrorCleansClosedSession() {
        TestSocket socket = new TestSocket();
        int base = JxWebSocket.totalConnections();
        var st = new TestStubs.SessionState();
        Session s = TestStubs.session(st);
        socket._onOpen(s);
        socket.join("sala-x", s);
        st.open = false;
        socket._onError(s, new RuntimeException("conexión rota"));
        check("onError limpia sesión cerrada", JxWebSocket.totalConnections() == base);
    }

    static void testConcurrentBroadcast() throws Exception {
        TestSocket socket = new TestSocket();
        List<TestStubs.SessionState> states = new ArrayList<>();
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var st = new TestStubs.SessionState();
            Session s = TestStubs.session(st);
            socket._onOpen(s);
            socket.join("sala-c", s);
            states.add(st);
            sessions.add(s);
        }

        int threads = 10, perThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) socket.broadcastTo("sala-c", "msg");
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        int expected = threads * perThread;
        boolean allReceived = states.stream().allMatch(st -> st.sent.size() == expected);
        check("broadcast concurrente sin errores", errors.get() == 0);
        check("cada sesión recibe todos los mensajes", allReceived);
        for (Session s : sessions) socket._onClose(s);
    }
}
