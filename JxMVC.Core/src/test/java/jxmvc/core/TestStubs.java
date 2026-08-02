package jxmvc.core;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestStubs {

    private TestStubs() {}

    public static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class)     return 0;
        if (type == long.class)    return 0L;
        if (type == double.class)  return 0d;
        if (type == float.class)   return 0f;
        if (type == short.class)   return (short) 0;
        if (type == byte.class)    return (byte) 0;
        if (type == char.class)    return (char) 0;
        return null;
    }

    public static final class ResponseState {
        public final ByteArrayOutputStream out = new ByteArrayOutputStream();
        public final Map<String, String> headers = new LinkedHashMap<>();
        public String contentType;
        public int contentLength = -1;
    }

    public static HttpServletResponse response(ResponseState st) {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getOutputStream"  -> outputStream(st.out);
            case "setHeader"        -> { st.headers.put((String) args[0], (String) args[1]); yield null; }
            case "getHeader"        -> st.headers.get(args[0]);
            case "setContentType"   -> { st.contentType = (String) args[0]; yield null; }
            case "getContentType"   -> st.contentType;
            case "setContentLength" -> { st.contentLength = (int) args[0]; yield null; }
            case "isCommitted"      -> false;
            case "hashCode"         -> System.identityHashCode(proxy);
            case "equals"           -> proxy == args[0];
            case "toString"         -> "StubResponse";
            default                 -> defaultValue(method.getReturnType());
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                TestStubs.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, h);
    }

    private static ServletOutputStream outputStream(ByteArrayOutputStream target) {
        return new ServletOutputStream() {
            @Override public void write(int b)                       { target.write(b); }
            @Override public void write(byte[] b, int off, int len)  { target.write(b, off, len); }
            @Override public boolean isReady()                       { return true; }
            @Override public void setWriteListener(WriteListener w)  {}
        };
    }

    public static final class SessionState {
        public final List<String> sent = Collections.synchronizedList(new java.util.ArrayList<>());
        public volatile boolean open = true;
        public final Map<String, Object> userProps = new ConcurrentHashMap<>();
    }

    private static final AtomicInteger SESSION_IDS = new AtomicInteger();

    public static Session session(SessionState st) {
        String id = "stub-" + SESSION_IDS.incrementAndGet();
        InvocationHandler basic = (proxy, method, args) -> switch (method.getName()) {
            case "sendText"  -> { st.sent.add((String) args[0]); yield null; }
            case "hashCode"  -> System.identityHashCode(proxy);
            case "equals"    -> proxy == args[0];
            case "toString"  -> "StubBasicRemote";
            default          -> defaultValue(method.getReturnType());
        };
        Object remote = Proxy.newProxyInstance(
                TestStubs.class.getClassLoader(), new Class<?>[]{RemoteEndpoint.Basic.class}, basic);

        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getId"             -> id;
            case "isOpen"            -> st.open;
            case "close"             -> { st.open = false; yield null; }
            case "getBasicRemote"    -> remote;
            case "getUserProperties" -> st.userProps;
            case "getPathParameters" -> Map.of();
            case "getRequestURI"     -> URI.create("/ws/test");
            case "hashCode"          -> System.identityHashCode(proxy);
            case "equals"            -> proxy == args[0];
            case "toString"          -> "StubSession[" + id + "]";
            default                  -> defaultValue(method.getReturnType());
        };
        return (Session) Proxy.newProxyInstance(
                TestStubs.class.getClassLoader(), new Class<?>[]{Session.class}, h);
    }
}
