package cero.http;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import javax.net.ssl.SSLSocket;

final class Connection implements Runnable, Watchdog.Vigilada {

    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final Socket socket;
    private final ServerContext context;
    private final Consumer<Connection> release;

    private volatile long limiteNanos;

    private volatile OutgoingResponse enCurso;

    private volatile boolean ociosa = true;

    private AsciiBuffer head;

    private PushbackInputStream entrada;

    Connection(Socket socket, ServerContext context, Consumer<Connection> release) {
        this.socket = socket;
        this.context = context;
        this.release = release;
    }

    @Override
    public long limiteNanos() {
        OutgoingResponse respuesta = enCurso;
        return respuesta != null && respuesta.upgraded() ? respuesta.escrituraHasta() : limiteNanos;
    }

    @Override
    public void run() {
        ServerOptions options = context.options();
        try (Socket open = socket) {
            open.setTcpNoDelay(true);
            open.setSoTimeout(options.idleTimeoutMillis());

            OutputStream out = new BufferedOutputStream(open.getOutputStream(), 16_384);

            if (options.http2() && open instanceof SSLSocket cifrado
                    && "h2".equals(cifrado.getApplicationProtocol())) {
                ociosa = false;
                Http2.servir(open, open.getInputStream(), out, context, remoteAddress(open));
                return;
            }

            PushbackInputStream entrada =
                    new PushbackInputStream(open.getInputStream(), Http2.PREAMBULO.length);
            if (options.http2() && Http2.pareceHttp2(entrada)) {
                ociosa = false;
                Http2.servir(open, entrada, out, context, remoteAddress(open));
                return;
            }

            this.entrada = entrada;
            var reader = new ByteReader(entrada, options.readBufferBytes());
            var requests = new RequestReader(reader, context);
            head = new AsciiBuffer(512);

            context.watchdog().vigilar(this);
            try {
                serve(requests, reader, out, remoteAddress(open));
            } finally {
                context.watchdog().soltar(this);
            }
        } catch (IOException cause) {
            context.reporter().transport(cause);
        } finally {
            release.accept(this);
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
            ociosa = false;

            if (served == 0 && options.http2() && Http2.pidenUpgrade(request)) {
                Http2.aceptarUpgrade(socket, entrada, out, context, remote, request);
                return;
            }

            boolean last = served + 1 == options.maxKeepAliveRequests();
            boolean keepAlive = wantsKeepAlive(request) && !last && context.accepting().getAsBoolean();

            var response = new OutgoingResponse(out, request, options, head);
            enCurso = response;
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
            ociosa = true;
        }
    }

    boolean cerrarSiOciosa() {
        if (!ociosa) {
            return false;
        }
        try {
            socket.close();
        } catch (IOException ignorado) {
        }
        return true;
    }

    private boolean guarded(IncomingRequest request, OutgoingResponse response, OutputStream out) {
        int timeout = context.options().handlerTimeoutMillis();
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
        } catch (IOException | HttpException cause) {
            return false;
        }
    }

    private void writeError(OutputStream out, int status, String message) {
        try {
            var response = new OutgoingResponse(out, null, context.options(), head);
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
