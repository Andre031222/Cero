package org.postgresql;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class Driver implements java.sql.Driver {

    public static final AtomicInteger opened          = new AtomicInteger();
    public static final AtomicInteger validationCalls = new AtomicInteger();
    public static final Set<Connection> closed =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    static {
        try { DriverManager.registerDriver(new Driver()); } catch (SQLException ignored) {}
    }

    public static void reset() {
        opened.set(0);
        validationCalls.set(0);
        closed.clear();
    }

    @Override public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    @Override public Connection connect(String url, Properties info) {
        if (!acceptsURL(url)) return null;
        opened.incrementAndGet();
        return newConnection();
    }

    public static Connection newConnection() {
        boolean[] state = { false };
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "isClosed"  -> state[0];
            case "isValid"   -> { validationCalls.incrementAndGet(); yield !state[0]; }
            case "close"     -> { state[0] = true; closed.add((Connection) proxy); yield null; }
            case "hashCode"  -> System.identityHashCode(proxy);
            case "equals"    -> proxy == args[0];
            case "toString"  -> "StubConnection@" + System.identityHashCode(proxy);
            default          -> jxmvc.core.TestStubs.defaultValue(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(
                Driver.class.getClassLoader(), new Class<?>[]{Connection.class}, h);
    }

    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
}
