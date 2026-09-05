package cero.http;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class Connection implements Runnable, Watchdog.Vigilada {

    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final Socket socket;
    private final ServerContext context;
    private final Consumer<Connection> release;

    /** Instante en que vence la petición en vuelo, o 0 si no hay ninguna. Lo lee el watchdog. */
    private volatile long limiteNanos;

    /**
     * Cierto mientras la conexión solo espera la siguiente petición.
     *
     * <p>Lo lee el apagado, y por eso no vale reutilizar {@link #limiteNanos}: ese solo se marca
     * cuando hay límite de manejador puesto, y con {@code handlerTimeoutMillis} a cero una
     * petición en vuelo pasaría por ociosa y se le cortaría el socket a media respuesta.
     */
    private volatile boolean ociosa = true;

    /** Buffer de cabeceras compartido por todas las peticiones de esta conexión. */
    private AsciiBuffer head;

    /** El flujo crudo, que hace falta si la conexión cambia a HTTP/2 a mitad. */
    private java.io.PushbackInputStream entrada;

    Connection(Socket socket, ServerContext context, Consumer<Connection> release) {
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

            // Sobre TLS no hay que adivinar nada: el protocolo se acordó en el apretón de manos
            // por ALPN, y preguntarlo es más fiable que mirar los primeros bytes. Es también el
            // único camino por el que un navegador llega a HTTP/2.
            if (open instanceof javax.net.ssl.SSLSocket cifrado
                    && "h2".equals(cifrado.getApplicationProtocol())) {
                ociosa = false;
                Http2.servir(open, open.getInputStream(), out, context, remoteAddress(open));
                return;
            }

            // En claro sí hay que mirar: el preámbulo se ojea y se devuelve al flujo. Es la única
            // forma de distinguir h2c de HTTP/1.1, porque los dos empiezan por texto ASCII y
            // «PRI * HTTP/2.0» es una petición sintácticamente válida en HTTP/1.1.
            java.io.PushbackInputStream entrada =
                    new java.io.PushbackInputStream(open.getInputStream(), Http2.PREAMBULO.length);
            if (Http2.pareceHttp2(entrada)) {
                ociosa = false;
                Http2.servir(open, entrada, out, context, remoteAddress(open));
                return;
            }

            this.entrada = entrada;
            ByteReader reader = new ByteReader(entrada, options.readBufferBytes());
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
            // Desde aquí hay algo que terminar: el apagado ya no puede cortar esta conexión.
            ociosa = false;

            // `Upgrade: h2c` es la otra puerta a HTTP/2, y la que usa un cliente que no sabe de
            // antemano si el servidor lo habla. Solo vale en la primera petición de la conexión:
            // después ya se ha hablado HTTP/1.1 y cambiar a mitad no está definido.
            if (served == 0 && Http2.pidenUpgrade(request)) {
                Http2.aceptarUpgrade(socket, entrada, out, context, remote, request);
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
            ociosa = true;
        }
    }

    /**
     * Cierra el socket si la conexión solo estaba esperando. Lo llama {@link Server#stop()}.
     *
     * <p>Una conexión con keep-alive que espera la siguiente petición está bloqueada leyendo, y
     * {@code shutdown()} no la interrumpe. Sin esto, el apagado gasta la ventana de gracia entera
     * en no esperar a nadie: diez segundos por despliegue, y otros diez por cada servidor que
     * arranca y para la batería de pruebas.
     *
     * @return si se cerró, es decir, si no había nada en vuelo
     */
    boolean cerrarSiOciosa() {
        if (!ociosa) {
            return false;
        }
        try {
            socket.close();
        } catch (IOException ignorado) {
            // Cerrando un socket que ya estaba roto: es justo lo que se quería.
        }
        return true;
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
