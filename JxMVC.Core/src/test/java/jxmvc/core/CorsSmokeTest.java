package jxmvc.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class CorsSmokeTest {

    private CorsSmokeTest() {
    }

    static class NoCorsController {
        public void open() {
        }
    }

    static class DemoCorsController {
        @JxMapping.JxCors(
                origins = {"http://localhost:5173"},
                hosts = {"127.0.0.1", "::1"},
                methods = {"GET"}
        )
        public void local() {
        }

        @JxMapping.JxCors(origins = {"*"}, methods = {"GET"})
        public void publicRead() {
        }
    }

    public static void main(String[] args) throws Exception {
        BaseCorsResolver resolver = new BaseCorsResolver();

        Method open = NoCorsController.class.getDeclaredMethod("open");
        Method local = DemoCorsController.class.getDeclaredMethod("local");
        Method publicRead = DemoCorsController.class.getDeclaredMethod("publicRead");

        assertFalse(
                resolver.applyAndCheck(
                        request("GET", "127.0.0.1", "http://localhost:5173", null, null),
                        response().proxy(),
                        NoCorsController.class,
                        open
                ),
                "Cross-origin without @JxCors must be blocked"
        );

        ResponseStub localResponse = response();
        assertTrue(
                resolver.applyAndCheck(
                        request("GET", "127.0.0.1", "http://localhost:5173", null, null),
                        localResponse.proxy(),
                        DemoCorsController.class,
                        local
                ),
                "Annotated local GET must be allowed"
        );
        assertEquals("http://localhost:5173", localResponse.headers.get("Access-Control-Allow-Origin"), "Local origin header");

        assertFalse(
                resolver.applyAndCheck(
                        request("POST", "127.0.0.1", "http://localhost:5173", null, null),
                        response().proxy(),
                        DemoCorsController.class,
                        local
                ),
                "Wrong method must be blocked"
        );

        ResponseStub publicResponse = response();
        assertTrue(
                resolver.applyAndCheck(
                        request("GET", "10.10.10.10", "https://any.app", null, null),
                        publicResponse.proxy(),
                        DemoCorsController.class,
                        publicRead
                ),
                "Public read should allow wildcard origin"
        );
        assertEquals("*", publicResponse.headers.get("Access-Control-Allow-Origin"), "Wildcard origin header");

        System.out.println("CorsSmokeTest OK");
    }

    private static HttpServletRequest request(
            String method,
            String remoteAddr,
            String origin,
            String requestedMethod,
            String requestedHeaders
    ) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", origin);
        headers.put("Access-Control-Request-Method", requestedMethod);
        headers.put("Access-Control-Request-Headers", requestedHeaders);

        InvocationHandler handler = (proxy, invoked, args) -> {
            return switch (invoked.getName()) {
                case "getMethod" -> method;
                case "getRemoteAddr" -> remoteAddr;
                case "getHeader" -> headers.get(String.valueOf(args[0]));
                default -> defaultValue(invoked.getReturnType());
            };
        };

        return (HttpServletRequest) Proxy.newProxyInstance(
                CorsSmokeTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                handler
        );
    }

    private static ResponseStub response() {
        return new ResponseStub();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class || type == short.class || type == int.class || type == long.class) {
            return 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new IllegalStateException(message);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static final class ResponseStub {
        private final Map<String, String> headers = new HashMap<>();

        private HttpServletResponse proxy() {
            InvocationHandler handler = (proxy, invoked, args) -> {
                if ("setHeader".equals(invoked.getName())) {
                    headers.put(String.valueOf(args[0]), String.valueOf(args[1]));
                    return null;
                }
                return defaultValue(invoked.getReturnType());
            };

            return (HttpServletResponse) Proxy.newProxyInstance(
                    CorsSmokeTest.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},
                    handler
            );
        }
    }
}
