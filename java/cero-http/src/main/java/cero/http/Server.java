package cero.http;

import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Server implements AutoCloseable {

    private final ServerOptions options;
    private final ServerSocket socket;
    private final ExecutorService connections;
    private final Watchdog watchdog;
    private final Sessions sessions;
    private final Thread acceptor;
    private final ServerContext context;
    private final AtomicInteger active = new AtomicInteger();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private volatile boolean running = true;

    public static Server start(int port, Handler handler) {
        return start(ServerOptions.builder().port(port).build(), handler, ErrorReporter.standardError());
    }

    public static Server start(ServerOptions options, Handler handler) {
        return start(options, handler, ErrorReporter.standardError());
    }

    public static Server start(ServerOptions options, Handler handler, ErrorReporter reporter) {
        return new Server(options, handler, reporter);
    }

    private Server(ServerOptions options, Handler handler, ErrorReporter reporter) {
        this.options = options;
        this.socket = open(options);
        this.watchdog = new Watchdog(options.handlerTimeoutMillis());
        this.connections = Executors.newVirtualThreadPerTaskExecutor();
        this.sessions = new Sessions(options.sessionTimeoutMillis(), options.sessionStore(),
                options.sessionMaxLifetimeMillis());
        this.context = new ServerContext(options, handler, reporter, watchdog, sessions, () -> running);
        this.acceptor = Thread.ofPlatform().name("cero-accept").daemon(false).start(this::acceptLoop);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public String host() {
        return options.host();
    }

    public boolean secure() {
        return options.secure();
    }

    public int activeConnections() {
        return active.get();
    }

    public boolean running() {
        return running;
    }

    public void await() throws InterruptedException {
        stopped.await();
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        closeQuietly(socket);
        connections.shutdown();
        try {
            if (!connections.awaitTermination(options.shutdownGraceMillis(), TimeUnit.MILLISECONDS)) {
                connections.shutdownNow();
            }
            acceptor.join(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            watchdog.close();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static ServerSocket open(ServerOptions options) {
        try {
            ServerSocket opened = options.secure()
                    ? options.tls().getServerSocketFactory().createServerSocket()
                    : new ServerSocket();
            if (opened instanceof SSLServerSocket secure) {
                secure.setUseClientMode(false);
            }
            opened.setReuseAddress(true);
            opened.bind(new InetSocketAddress(options.host(), options.port()), options.backlog());
            return opened;
        } catch (IOException cause) {
            throw new UncheckedIOException("no se pudo abrir el puerto " + options.port(), cause);
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException cause) {
                if (!running || socket.isClosed()) {
                    break;
                }
                context.reporter().transport(cause);
                continue;
            }
            if (active.incrementAndGet() > options.maxConnections()) {
                active.decrementAndGet();
                closeQuietly(client);
                continue;
            }
            try {
                connections.execute(new Connection(client, context, active::decrementAndGet));
            } catch (RejectedExecutionException rejected) {
                active.decrementAndGet();
                closeQuietly(client);
            }
        }
        stopped.countDown();
    }

    private static void closeQuietly(AutoCloseable target) {
        try {
            target.close();
        } catch (Exception ignored) {
        }
    }
}
