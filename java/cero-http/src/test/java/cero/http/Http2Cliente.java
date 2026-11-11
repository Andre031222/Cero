package cero.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Http2Cliente implements AutoCloseable {

    static final int DATA = 0x0;
    static final int HEADERS = 0x1;
    static final int PRIORITY = 0x2;
    static final int RST_STREAM = 0x3;
    static final int SETTINGS = 0x4;
    static final int PUSH_PROMISE = 0x5;
    static final int PING = 0x6;
    static final int GOAWAY = 0x7;
    static final int WINDOW_UPDATE = 0x8;
    static final int CONTINUATION = 0x9;

    static final int FIN_FLUJO = 0x1;
    static final int FIN_CABECERAS = 0x4;
    static final int RECONOCE = 0x1;

    record Trama(int tipo, int banderas, int flujo, byte[] carga) {

        int comoEntero() {
            return ((carga[0] & 0xFF) << 24) | ((carga[1] & 0xFF) << 16)
                    | ((carga[2] & 0xFF) << 8) | (carga[3] & 0xFF);
        }

        int codigoDeError() {
            int desde = tipo == GOAWAY ? 4 : 0;
            return ((carga[desde] & 0xFF) << 24) | ((carga[desde + 1] & 0xFF) << 16)
                    | ((carga[desde + 2] & 0xFF) << 8) | (carga[desde + 3] & 0xFF);
        }
    }

    record Respuesta(Map<String, String> cabeceras, byte[] cuerpo) {

        int estado() {
            return Integer.parseInt(cabeceras.getOrDefault(":status", "0"));
        }

        String texto() {
            return new String(cuerpo, StandardCharsets.UTF_8);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Hpack.Codificador codificador = new Hpack.Codificador();
    private final Hpack.Decodificador decodificador = new Hpack.Decodificador(Hpack.TAMANO_TABLA, Integer.MAX_VALUE, Integer.MAX_VALUE);
    private int siguienteFlujo = 1;

    private final Map<Integer, Parcial> enCurso = new LinkedHashMap<>();

    private static final class Parcial {
        final Map<String, String> cabeceras = new LinkedHashMap<>();
        final ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        boolean terminado;
        int cortadoCon = -1;
    }

    Http2Cliente(int puerto) throws IOException {
        socket = new Socket("127.0.0.1", puerto);
        socket.setSoTimeout(10_000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    Http2Cliente saludar() throws IOException {
        out.write(Http2.PREAMBULO);
        trama(SETTINGS, 0, 0, new byte[0]);
        return this;
    }

    Http2Cliente preambulo() throws IOException {
        out.write(Http2.PREAMBULO);
        out.flush();
        return this;
    }

    void bytes(byte[] crudos) throws IOException {
        out.write(crudos);
        out.flush();
    }

    void trama(int tipo, int banderas, int flujo, byte[] carga) throws IOException {
        out.write(new byte[] {
                (byte) (carga.length >>> 16), (byte) (carga.length >>> 8), (byte) carga.length,
                (byte) tipo, (byte) banderas,
                (byte) (flujo >>> 24), (byte) (flujo >>> 16), (byte) (flujo >>> 8), (byte) flujo });
        out.write(carga);
        out.flush();
    }

    void tramaConLargoFalso(int tipo, int banderas, int flujo, int largoDeclarado, byte[] carga)
            throws IOException {
        out.write(new byte[] {
                (byte) (largoDeclarado >>> 16), (byte) (largoDeclarado >>> 8), (byte) largoDeclarado,
                (byte) tipo, (byte) banderas,
                (byte) (flujo >>> 24), (byte) (flujo >>> 16), (byte) (flujo >>> 8), (byte) flujo });
        out.write(carga);
        out.flush();
    }

    byte[] cabeceras(String... paresPlanos) {
        List<Hpack.Campo> campos = new ArrayList<>();
        for (int i = 0; i < paresPlanos.length; i += 2) {
            campos.add(new Hpack.Campo(paresPlanos[i], paresPlanos[i + 1]));
        }
        return codificador.codificar(campos);
    }

    byte[] cabecerasCrudas(String... paresPlanos) {
        var salida = new ByteArrayOutputStream();
        for (int i = 0; i < paresPlanos.length; i += 2) {
            byte[] nombre = paresPlanos[i].getBytes(StandardCharsets.ISO_8859_1);
            byte[] valor = paresPlanos[i + 1].getBytes(StandardCharsets.ISO_8859_1);
            salida.write(0x00);
            salida.write(nombre.length);
            salida.write(nombre, 0, nombre.length);
            salida.write(valor.length);
            salida.write(valor, 0, valor.length);
        }
        return salida.toByteArray();
    }

    byte[] bombaHpack(int tamanoValor, int veces) {
        var salida = new ByteArrayOutputStream();
        byte[] nombre = "x-grande".getBytes(StandardCharsets.ISO_8859_1);
        byte[] valor = "v".repeat(tamanoValor).getBytes(StandardCharsets.ISO_8859_1);

        salida.write(0x40);
        salida.write(nombre.length);
        salida.write(nombre, 0, nombre.length);
        int resto = valor.length;
        salida.write(0x7F);
        resto -= 127;
        while (resto >= 0x80) {
            salida.write((resto & 0x7F) | 0x80);
            resto >>>= 7;
        }
        salida.write(resto);
        salida.write(valor, 0, valor.length);

        for (int i = 0; i < veces; i++) {
            salida.write(0x80 | 62);
        }
        return salida.toByteArray();
    }

    int pedir(String metodo, String ruta, String... cabecerasExtra) throws IOException {
        int flujo = siguienteFlujo;
        siguienteFlujo += 2;
        String[] base = { ":method", metodo, ":scheme", "http", ":path", ruta,
                          ":authority", "127.0.0.1" };
        String[] todos = new String[base.length + cabecerasExtra.length];
        System.arraycopy(base, 0, todos, 0, base.length);
        System.arraycopy(cabecerasExtra, 0, todos, base.length, cabecerasExtra.length);
        trama(HEADERS, FIN_CABECERAS | FIN_FLUJO, flujo, cabeceras(todos));
        return flujo;
    }

    int pedirCon(String metodo, String ruta, byte[] cuerpo) throws IOException {
        int flujo = siguienteFlujo;
        siguienteFlujo += 2;
        trama(HEADERS, FIN_CABECERAS, flujo, cabeceras(
                ":method", metodo, ":scheme", "http", ":path", ruta, ":authority", "127.0.0.1"));
        int pos = 0;
        do {
            int hasta = Math.min(pos + 16_384, cuerpo.length);
            boolean ultimo = hasta == cuerpo.length;
            trama(DATA, ultimo ? FIN_FLUJO : 0, flujo,
                    Arrays.copyOfRange(cuerpo, pos, hasta));
            pos = hasta;
        } while (pos < cuerpo.length);
        if (cuerpo.length == 0) {
            trama(DATA, FIN_FLUJO, flujo, new byte[0]);
        }
        return flujo;
    }

    int pedirSinCerrar(String metodo, String ruta, String... cabecerasExtra) throws IOException {
        int flujo = siguienteFlujo;
        siguienteFlujo += 2;
        String[] base = { ":method", metodo, ":scheme", "http", ":path", ruta,
                          ":authority", "127.0.0.1" };
        String[] todos = new String[base.length + cabecerasExtra.length];
        System.arraycopy(base, 0, todos, 0, base.length);
        System.arraycopy(cabecerasExtra, 0, todos, base.length, cabecerasExtra.length);
        trama(HEADERS, FIN_CABECERAS, flujo, cabeceras(todos));
        return flujo;
    }

    Trama leerTrama() throws IOException {
        byte[] cabecera = new byte[9];
        leerDelTodo(cabecera);
        int largo = ((cabecera[0] & 0xFF) << 16) | ((cabecera[1] & 0xFF) << 8) | (cabecera[2] & 0xFF);
        byte[] carga = new byte[largo];
        leerDelTodo(carga);
        return new Trama(cabecera[3] & 0xFF, cabecera[4] & 0xFF,
                ((cabecera[5] & 0x7F) << 24) | ((cabecera[6] & 0xFF) << 16)
                        | ((cabecera[7] & 0xFF) << 8) | (cabecera[8] & 0xFF), carga);
    }

    Trama esperar(int... tipos) throws IOException {
        for (int intento = 0; intento < 64; intento++) {
            Trama t = leerTrama();
            for (int tipo : tipos) {
                if (t.tipo() == tipo) {
                    return t;
                }
            }
        }
        throw new IOException("no llegó ninguna trama de los tipos pedidos");
    }

    Respuesta respuestaDe(int flujo) throws IOException {
        for (int intento = 0; intento < 100_000; intento++) {
            Parcial p = enCurso.get(flujo);
            if (p != null && p.cortadoCon >= 0) {
                throw new IOException("el flujo " + flujo + " se cortó con código " + p.cortadoCon);
            }
            if (p != null && p.terminado) {
                enCurso.remove(flujo);
                return new Respuesta(p.cabeceras, p.cuerpo.toByteArray());
            }
            archivar(leerTrama());
        }
        throw new IOException("la respuesta del flujo " + flujo + " no terminó");
    }

    private void archivar(Trama t) throws IOException {
        if (t.tipo() == GOAWAY) {
            for (Parcial p : enCurso.values()) {
                p.cortadoCon = t.codigoDeError();
            }
            throw new IOException("el servidor cerró la conexión con código " + t.codigoDeError());
        }
        if (t.flujo() == 0) {
            return;
        }
        Parcial p = enCurso.computeIfAbsent(t.flujo(), k -> new Parcial());
        switch (t.tipo()) {
            case HEADERS, CONTINUATION -> {
                for (Hpack.Campo c : decodificador.decodificar(t.carga())) {
                    p.cabeceras.put(c.nombre(), c.valor());
                }
            }
            case DATA -> {
                p.cuerpo.write(t.carga());
                if (t.carga().length > 0) {
                    trama(WINDOW_UPDATE, 0, 0, entero(t.carga().length));
                    trama(WINDOW_UPDATE, 0, t.flujo(), entero(t.carga().length));
                }
            }
            case RST_STREAM -> p.cortadoCon = t.codigoDeError();
            default -> { }
        }
        if ((t.banderas() & FIN_FLUJO) != 0 && (t.tipo() == HEADERS || t.tipo() == DATA)) {
            p.terminado = true;
        }
    }

    void abrirVentanas(int cuanto) throws IOException {
        trama(WINDOW_UPDATE, 0, 0, entero(cuanto));
        trama(SETTINGS, 0, 0, new byte[] {
                0, 0x4, (byte) (cuanto >>> 24), (byte) (cuanto >>> 16),
                (byte) (cuanto >>> 8), (byte) cuanto });
    }

    static byte[] entero(int valor) {
        return new byte[] { (byte) (valor >>> 24), (byte) (valor >>> 16),
                            (byte) (valor >>> 8), (byte) valor };
    }

    private void leerDelTodo(byte[] destino) throws IOException {
        int leidos = 0;
        while (leidos < destino.length) {
            int n = in.read(destino, leidos, destino.length - leidos);
            if (n < 0) {
                throw new EOFException("el servidor cerró");
            }
            leidos += n;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
