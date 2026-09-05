package cero.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * La respuesta de un flujo HTTP/2.
 *
 * <p>Implementa la misma interfaz {@link Response} que la de HTTP/1.1, así que un controlador no
 * distingue por cuál está respondiendo. Lo que cambia es la salida: en vez de una línea de estado
 * y cabeceras en texto, una trama HEADERS con el bloque HPACK y luego tramas DATA.
 *
 * <p>Tres cosas que en HTTP/2 no existen y aquí se traducen:
 *
 * <ul>
 *   <li><b>No hay línea de estado.</b> El código va como el pseudo-campo {@code :status}, y va
 *       primero: el RFC exige que los pseudo-campos precedan a los normales.
 *   <li><b>No hay cabeceras de conexión.</b> {@code Connection}, {@code Keep-Alive} y
 *       {@code Transfer-Encoding} se filtran al salir; mandarlas es malformar la respuesta.
 *   <li><b>Los nombres van en minúscula.</b> No es estilo: en mayúscula la respuesta es inválida.
 * </ul>
 */
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

    // ─── la interfaz ─────────────────────────────────────────────────────────────────────────

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

    /**
     * En HTTP/2 no se cambia de protocolo con un 101.
     *
     * <p>WebSocket sobre h2 es otra cosa —el CONNECT extendido del RFC 8441— y no está. Decirlo
     * con un fallo claro es mejor que devolver un 101 que el cliente no puede interpretar: en h2
     * ese código no significa nada.
     */
    @Override
    public Duplex switchProtocols() {
        throw new HttpException(501,
                "cambiar de protocolo no existe en HTTP/2; para WebSocket usa HTTP/1.1");
    }

    @Override
    public boolean committed() {
        return comprometida;
    }

    // ─── salida ──────────────────────────────────────────────────────────────────────────────

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

    /** Los pseudo-campos primero y en minúscula, sin las cabeceras que h2 prohíbe. */
    private List<Hpack.Campo> campos() {
        List<Hpack.Campo> campos = new ArrayList<>();
        campos.add(new Hpack.Campo(":status", String.valueOf(estado)));
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

    /** Lo que el manejador termine sin haber respondido acaba en un 204 vacío, no colgado. */
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

    /**
     * La salida por {@code stream()}: sale en tramas según se escribe, no al final.
     *
     * <p>Antes se acumulaba entera en memoria, lo que convertía una respuesta de streaming en una
     * respuesta normal con un rodeo — y hacía imposible mandar algo más grande que el montón, que
     * es justo para lo que existe {@code stream()}.
     *
     * <p>Va con un buffer intermedio de 8 KB por una razón concreta: cada trama DATA cuesta nueve
     * octetos de cabecera y una espera de ventana, así que un manejador que escriba de byte en
     * byte generaría una trama por byte. El buffer las junta; {@code flush()} las suelta cuando
     * el manejador quiere que salgan de verdad —eventos del servidor, progreso— y ahí sí sale
     * inmediatamente.
     *
     * <p>Las cabeceras se mandan en la primera escritura, no al cerrar: un cliente que está
     * esperando el primer byte necesita el {@code :status} antes que el cuerpo.
     */
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

        /** Manda lo acumulado. Con `fin`, cierra el flujo en la misma trama. */
        private void soltar(boolean fin) throws IOException {
            if (!comprometida) {
                comprometida = true;
                // Sin `content-length`: en streaming no se sabe cuánto va a salir, y declararlo
                // mal es peor que no declararlo. El fin del flujo lo marca la bandera.
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
