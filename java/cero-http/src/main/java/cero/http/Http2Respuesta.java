package cero.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Http2Respuesta implements Response {

    private final Http2 conexion;
    private final Http2.Flujo flujo;
    private final IncomingRequest peticion;
    private final Headers cabeceras = new Headers();

    private int estado = 200;
    private boolean comprometida;
    private Flujo salidaEnCurso;

    Http2Respuesta(Http2 conexion, Http2.Flujo flujo, IncomingRequest peticion) {
        this.conexion = conexion;
        this.flujo = flujo;
        this.peticion = peticion;
    }

    @Override
    public Response status(int code) {
        estado = code;
        return this;
    }

    @Override
    public int status() {
        return estado;
    }

    @Override
    public Headers headers() {
        return cabeceras;
    }

    @Override
    public Response header(String name, String value) {
        cabeceras.set(name, value);
        return this;
    }

    @Override
    public Response type(String contentType) {
        return header("content-type", contentType);
    }

    @Override
    public Response cookie(Cookie cookie) {
        cabeceras.add("set-cookie", cookie.encode());
        return this;
    }

    @Override
    public void send(byte[] body) {
        emitir(body == null ? new byte[0] : body);
    }

    @Override
    public void send(String body) {
        send(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void text(String body) {
        siNoHay("content-type", "text/plain; charset=utf-8");
        send(body);
    }

    @Override
    public void html(String body) {
        siNoHay("content-type", "text/html; charset=utf-8");
        send(body);
    }

    @Override
    public void json(String body) {
        siNoHay("content-type", "application/json; charset=utf-8");
        send(body);
    }

    @Override
    public void redirect(String location) {
        if (location == null || location.startsWith("//") || location.contains("://")) {
            throw new HttpException(500, "redirección abierta: usa redirectExternal si es a propósito");
        }
        estado = 302;
        header("location", location);
        send(new byte[0]);
    }

    @Override
    public void redirectExternal(String location) {
        estado = 302;
        header("location", location);
        send(new byte[0]);
    }

    @Override
    public OutputStream stream() {
        if (salidaEnCurso == null) {
            salidaEnCurso = new Flujo();
        }
        return salidaEnCurso;
    }

    @Override
    public Duplex switchProtocols() {
        throw new HttpException(501,
                "cambiar de protocolo no existe en HTTP/2; para WebSocket usa HTTP/1.1");
    }

    @Override
    public boolean committed() {
        return comprometida;
    }

    private void siNoHay(String nombre, String valor) {
        if (cabeceras.get(nombre) == null) {
            cabeceras.set(nombre, valor);
        }
    }

    private void emitir(byte[] cuerpo) {
        if (comprometida) {
            throw new IllegalStateException("la respuesta ya salió");
        }
        comprometida = true;
        boolean conCuerpo = peticion.method() != HttpMethod.HEAD && cuerpo.length > 0
                && estado != 204 && estado != 304;
        try {
            cabeceras.set("content-length", String.valueOf(conCuerpo ? cuerpo.length : 0));
            conexion.mandarCabeceras(flujo.id, campos(), !conCuerpo);
            if (conCuerpo) {
                conexion.mandarDatos(flujo, cuerpo, true);
            }
        } catch (IOException roto) {
            throw new OutgoingResponse.UncheckedHttpException(roto);
        }
    }

    private List<Hpack.Campo> campos() {
        List<Hpack.Campo> campos = new ArrayList<>();
        campos.add(new Hpack.Campo(":status", String.valueOf(estado)));
        Cookie pendiente = peticion.pendingSessionCookie();
        if (pendiente != null) {
            campos.add(new Hpack.Campo("set-cookie", pendiente.encode()));
        }
        for (int i = 0; i < cabeceras.size(); i++) {
            String minuscula = cabeceras.name(i).toLowerCase(Locale.ROOT);
            if (minuscula.equals("connection") || minuscula.equals("keep-alive")
                    || minuscula.equals("transfer-encoding") || minuscula.equals("upgrade")
                    || minuscula.equals("proxy-connection")) {
                continue;
            }
            campos.add(new Hpack.Campo(minuscula, cabeceras.value(i)));
        }
        return campos;
    }

    void terminar() {
        if (salidaEnCurso != null) {
            salidaEnCurso.cerrarDeVerdad();
            return;
        }
        if (!comprometida) {
            estado = estado == 200 ? 204 : estado;
            emitir(new byte[0]);
        }
    }

    void error(int codigo, String mensaje) {
        if (comprometida) {
            return;
        }
        estado = codigo;
        cabeceras.set("content-type", "text/plain; charset=utf-8");
        emitir((mensaje == null ? HttpStatus.reason(codigo) : mensaje)
                .getBytes(StandardCharsets.UTF_8));
    }

    private final class Flujo extends OutputStream {

        private static final int BUFFER = 8 * 1024;

        private final ByteArrayOutputStream pendiente = new ByteArrayOutputStream(BUFFER);
        private boolean cerrado;

        @Override
        public void write(int b) throws IOException {
            pendiente.write(b);
            siLlenoSuelta();
        }

        @Override
        public void write(byte[] datos, int desde, int largo) throws IOException {
            pendiente.write(datos, desde, largo);
            siLlenoSuelta();
        }

        private void siLlenoSuelta() throws IOException {
            if (pendiente.size() >= BUFFER) {
                soltar(false);
            }
        }

        @Override
        public void flush() throws IOException {
            soltar(false);
        }

        private void soltar(boolean fin) throws IOException {
            if (!comprometida) {
                comprometida = true;
                cabeceras.remove("content-length");
                conexion.mandarCabeceras(flujo.id, campos(), false);
            }
            byte[] trozo = pendiente.toByteArray();
            pendiente.reset();
            if (trozo.length > 0 || fin) {
                conexion.mandarDatos(flujo, trozo, fin);
            }
        }

        @Override
        public void close() {
            cerrarDeVerdad();
        }

        void cerrarDeVerdad() {
            if (cerrado) {
                return;
            }
            cerrado = true;
            Http2Respuesta.this.salidaEnCurso = null;
            try {
                soltar(true);
            } catch (IOException roto) {
                throw new OutgoingResponse.UncheckedHttpException(roto);
            }
        }
    }
}
