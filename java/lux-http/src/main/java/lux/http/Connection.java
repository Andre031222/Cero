package lux.http;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

final class Connection implements Runnable {

    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final Socket socket;
    private final ServerOptions options;
    private final Handler handler;
    private final ErrorReporter reporter;

    Connection(Socket socket, ServerOptions options, Handler handler, ErrorReporter reporter) {
        this.socket = socket;
        this.options = options;
        this.handler = handler;
        this.reporter = reporter;
    }

    @Override
    public void run() {
        try (Socket open = socket) {
            open.setTcpNoDelay(true);
            open.setSoTimeout(options.idleTimeoutMillis());

            OutputStream out = new BufferedOutputStream(open.getOutputStream(), 16_384);
            ByteReader reader = new ByteReader(open.getInputStream(), options.readBufferBytes());
            RequestReader requests = new RequestReader(reader, options);
            String remote = remoteAddress(open);

            serve(requests, out, remote);
        } catch (IOException cause) {
            reporter.transport(cause);
        }
    }

    private void serve(RequestReader requests, OutputStream out, String remote) throws IOException {
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
            boolean keepAlive = wantsKeepAlive(request) && !last;

            OutgoingResponse response = new OutgoingResponse(out, request.method() == HttpMethod.HEAD);
            response.keepAlive(keepAlive);

            if (request.headers().contains("Expect", "100-continue")) {
                out.write(CONTINUE);
                out.flush();
            }

            if (!dispatch(request, response, out)) {
                return;
            }
            if (!keepAlive || !drain(request)) {
                return;
            }
        }
    }

    private boolean dispatch(IncomingRequest request, OutgoingResponse response, OutputStream out) {
        try {
            handler.handle(request, response);
            response.finish();
            return true;
        } catch (OutgoingResponse.UncheckedHttpException broken) {
            reporter.transport(broken);
            return false;
        } catch (HttpException failed) {
            return recover(response, out, failed.status(), failed.getMessage());
        } catch (Exception failed) {
            reporter.handler(request, failed);
            return recover(response, out, 500, "error interno");
        }
    }

    private boolean recover(OutgoingResponse response, OutputStream out, int status, String message) {
        if (response.committed()) {
            return false;
        }
        try {
            response.keepAlive(false);
            response.status(status);
            response.type("text/plain; charset=utf-8");
            response.send(message == null ? HttpStatus.reason(status) : message);
            out.flush();
        } catch (RuntimeException ignored) {
            return false;
        } catch (IOException ignored) {
            return false;
        }
        return false;
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
            OutgoingResponse response = new OutgoingResponse(out, false);
            response.keepAlive(false);
            response.status(status);
            response.type("text/plain; charset=utf-8");
            response.send(message == null ? HttpStatus.reason(status) : message);
            out.flush();
        } catch (RuntimeException | IOException ignored) {
            reporter.transport(ignored);
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
