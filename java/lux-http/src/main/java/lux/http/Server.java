package lux.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class Server implements AutoCloseable {

    private final ServerOptions options;
    private final Handler handler;
    private final ErrorReporter reporter;
    private final ServerSocket socket;
    private final ExecutorService connections;
    private final Thread acceptor;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private volatile boolean running = true;

    public static Server start(int port, Handler handler) {
        return start(ServerOptions.defaults().port(port), handler, ErrorReporter.standardError());
    }

    public static Server start(ServerOptions options, Handler handler) {
        return start(options, handler, ErrorReporter.standardError());
    }

    public static Server start(ServerOptions options, Handler handler, ErrorReporter reporter) {
        return new Server(options, handler, reporter);
    }

    private Server(ServerOptions options, Handler handler, ErrorReporter reporter) {
        this.options = options;
        this.handler = handler;
        this.reporter = reporter;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(options.host(), options.port()), options.backlog());
        } catch (IOException cause) {
            throw new UncheckedIOException("no se pudo abrir el puerto " + options.port(), cause);
        }
        connections = Executors.newVirtualThreadPerTaskExecutor();
        acceptor = Thread.ofPlatform().name("lux-accept").daemon(false).start(this::acceptLoop);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public String host() {
        return options.host();
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
            acceptor.join(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
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
                reporter.transport(cause);
                continue;
            }
            try {
                connections.execute(new Connection(client, options, handler, reporter));
            } catch (RejectedExecutionException rejected) {
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
