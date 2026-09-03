package cero.http;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

final class Connection implements Runnable, Watchdog.Vigilada {

    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final Socket socket;
    private final ServerContext context;
    private final Runnable release;

    /** Instante en que vence la petición en vuelo, o 0 si no hay ninguna. Lo lee el watchdog. */
    private volatile long limiteNanos;

    /** Buffer de cabeceras compartido por todas las peticiones de esta conexión. */
    private AsciiBuffer head;

    Connection(Socket socket, ServerContext context, Runnable release) {
        this.socket = socket;
        this.context = context;
        this.release = release;
    }

    @Override
    public long limiteNanos() {
        return limiteNanos;
    }

    @Override
    public void run() {
        ServerOptions options = context.options();
        try (Socket open = socket) {
            open.setTcpNoDelay(true);
            open.setSoTimeout(options.idleTimeoutMillis());

            OutputStream out = new BufferedOutputStream(open.getOutputStream(), 16_384);
            ByteReader reader = new ByteReader(open.getInputStream(), options.readBufferBytes());
            RequestReader requests = new RequestReader(reader, context);
            head = new AsciiBuffer(512);

            // Alta y baja una vez por conexión, no por petición.
            context.watchdog().vigilar(this);
            try {
                serve(requests, reader, out, remoteAddress(open));
            } finally {
                context.watchdog().soltar(this);
            }
        } catch (IOException cause) {
            context.reporter().transport(cause);
        } finally {
            release.run();
        }
    }

    private void serve(RequestReader requests, ByteReader reader, OutputStream out, String remote)
            throws IOException {
        ServerOptions options = context.options();

        for (int served = 0; served < options.maxKeepAliveRequests(); served++) {
            IncomingRequest request;
            try {
                request = requests.read(remote);
            } catch (SocketTimeoutException timeout) {
                return;
            } catch (HttpException rejected) {
                writeError(out, rejected.status(), rejected.getMessage());
                return;
            }
            if (request == null) {
                return;
            }

            boolean last = served + 1 == options.maxKeepAliveRequests();
            boolean keepAlive = wantsKeepAlive(request) && !last && context.accepting().getAsBoolean();

            OutgoingResponse response = new OutgoingResponse(out, request, options, head);
            response.source(reader);
            response.keepAlive(keepAlive);

            if (request.headers().contains("Expect", "100-continue")) {
                out.write(CONTINUE);
                out.flush();
            }

            if (!guarded(request, response, out) || response.upgraded()) {
                return;
            }
            if (!keepAlive || !drain(request)) {
                return;
            }
        }
    }

    private boolean guarded(IncomingRequest request, OutgoingResponse response, OutputStream out) {
        int timeout = context.options().handlerTimeoutMillis();
        if (timeout <= 0) {
            return dispatch(request, response, out);
        }
        // Todo el coste del vigilante en el camino caliente: dos escrituras de un volatile.
        // Un límite de 0 no vale como marca de «en vuelo», así que se evita ese valor.
        long limite = System.nanoTime() + timeout * 1_000_000L;
        limiteNanos = limite == 0 ? 1 : limite;
        try {
            return dispatch(request, response, out);
        } finally {
            limiteNanos = 0;
        }
    }

    private boolean dispatch(IncomingRequest request, OutgoingResponse response, OutputStream out) {
        try {
            context.handler().handle(request, response);
            response.finish();
            out.flush();
            return true;
        } catch (OutgoingResponse.UncheckedHttpException broken) {
            context.reporter().transport(broken);
            return false;
        } catch (HttpException failed) {
            return recover(response, out, failed.status(), failed.getMessage());
        } catch (Exception failed) {
            context.reporter().handler(request, failed);
            return recover(response, out, 500, "error interno");
        }
    }

    private boolean recover(OutgoingResponse response, OutputStream out, int status, String message) {
        if (response.committed()) {
            return false;
        }
        try {
            response.keepAlive(false);
            response.reset();
            response.status(status);
            response.type("text/plain; charset=utf-8");
            response.send(message == null ? HttpStatus.reason(status) : message);
            out.flush();
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
        return false;
    }

    @Override
    public void abortar() {
        // Se limpia primero para que el barrendero no vuelva a cortar la misma conexión.
        limiteNanos = 0;
        context.reporter().transport(new HttpException(504, "handler excedió el tiempo límite"));
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private boolean drain(IncomingRequest request) {
        if (request.bodyDrained()) {
            return true;
        }
        try {
            request.drainBody();
            return true;
        } catch (IOException cause) {
            return false;
        }
    }

    private void writeError(OutputStream out, int status, String message) {
        try {
            OutgoingResponse response = new OutgoingResponse(out, null, context.options(), head);
            response.keepAlive(false);
            response.status(status);
            response.type("text/plain; charset=utf-8");
            response.send(message == null ? HttpStatus.reason(status) : message);
            out.flush();
        } catch (RuntimeException | IOException failed) {
            context.reporter().transport(failed);
        }
    }

    private static boolean wantsKeepAlive(IncomingRequest request) {
        if (request.headers().contains("Connection", "close")) {
            return false;
        }
        if (request.protocol().equals("HTTP/1.0")) {
            return request.headers().contains("Connection", "keep-alive");
        }
        return true;
    }

    private static String remoteAddress(Socket socket) {
        if (socket.getRemoteSocketAddress() instanceof InetSocketAddress address && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "";
    }
}
