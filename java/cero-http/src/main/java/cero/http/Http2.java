package cero.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class Http2 {

    static final byte[] PREAMBULO =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int DATA = 0x0;
    private static final int HEADERS = 0x1;
    private static final int PRIORITY = 0x2;
    private static final int RST_STREAM = 0x3;
    private static final int SETTINGS = 0x4;
    private static final int PUSH_PROMISE = 0x5;
    private static final int PING = 0x6;
    private static final int GOAWAY = 0x7;
    private static final int WINDOW_UPDATE = 0x8;
    private static final int CONTINUATION = 0x9;

    private static final int FIN_FLUJO = 0x1;
    private static final int FIN_CABECERAS = 0x4;
    private static final int RECONOCE = 0x1;
    private static final int RELLENO = 0x8;
    private static final int PRIORIDAD = 0x20;

    private static final int SIN_ERROR = 0x0;
    private static final int ERROR_NINGUNO = 0x0;
    private static final int ERROR_PROTOCOLO = 0x1;
    private static final int ERROR_INTERNO = 0x2;
    private static final int ERROR_CONTROL_FLUJO = 0x3;
    private static final int ERROR_TAMANO_TRAMA = 0x6;
    private static final int ERROR_RECHAZADO = 0x7;
    private static final int ERROR_ANULADO = 0x8;
    private static final int ERROR_FLUJO_CERRADO = 0x5;
    private static final int ERROR_COMPRESION = 0x9;
    private static final int ERROR_CALMA = 0xb;

    private static final int TRAMA_MAXIMA = 16_384;
    private static final int VENTANA_INICIAL = 65_535;


    private static final int MAX_CONTINUACIONES = 64;

    private final Socket socketCrudo;
    private final InputStream in;
    private final OutputStream out;
    private final ServerContext context;
    private final String remoto;
    private final ExecutorService hilos;

    private final Hpack.Decodificador hpackIn = new Hpack.Decodificador(Hpack.TAMANO_TABLA);
    private final Hpack.Codificador hpackOut = new Hpack.Codificador();

    private final Map<Integer, Flujo> flujos = new ConcurrentHashMap<>();
    private final Object candadoEscritura = new Object();

    private final AtomicLong ventanaSalida = new AtomicLong(VENTANA_INICIAL);

    private int tramaMaxima = TRAMA_MAXIMA;
    private int ventanaInicialFlujo = VENTANA_INICIAL;
    private final int maxFlujos;
    private final int maxBloqueCabeceras;
    private final int maxListaCabeceras;
    private final int maxAnulados;
    private final int maxControlSeguidas;

    private int ultimoFlujoVisto;
    private volatile boolean vivo = true;

    private int anulados;

    private int controlSeguidas;

    private Http2(Socket socket, InputStream in, OutputStream out, ServerContext context,
                  String remoto, ExecutorService hilos) {
        this.socketCrudo = socket;
        this.in = in;
        this.out = out;
        this.context = context;
        this.remoto = remoto;
        this.hilos = hilos;
        ServerOptions o = context.options();
        this.maxFlujos = o.http2MaxFlujos();
        this.maxBloqueCabeceras = o.http2MaxBloqueCabeceras();
        this.maxListaCabeceras = o.http2MaxListaCabeceras();
        this.maxAnulados = o.http2MaxAnulados();
        this.maxControlSeguidas = o.http2MaxControlSeguidas();
    }

    static void servir(Socket socket, InputStream in, OutputStream out, ServerContext context,
                       String remoto) throws IOException {
        try (ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            new Http2(socket, in, out, context, remoto, hilos).correr(null);
        }
    }

    static boolean pidenUpgrade(IncomingRequest peticion) {
        String upgrade = peticion.header("Upgrade");
        return upgrade != null && upgrade.trim().equalsIgnoreCase("h2c")
                && peticion.header("HTTP2-Settings") != null
                && peticion.headers().contains("Connection", "Upgrade")
                && peticion.headers().contains("Connection", "HTTP2-Settings");
    }

    static void aceptarUpgrade(Socket socket, InputStream in, OutputStream out,
                               ServerContext context, String remoto, IncomingRequest peticion)
            throws IOException {
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: h2c\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        try (ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2 conexion = new Http2(socket, in, out, context, remoto, hilos);
            conexion.ajustesDeCabecera(peticion.header("HTTP2-Settings"));
            conexion.correr(comoHttp2(peticion, context));
        }
    }

    private static IncomingRequest comoHttp2(IncomingRequest original, ServerContext context) {
        Headers limpias = new Headers(original.headers().size());
        for (int i = 0; i < original.headers().size(); i++) {
            String nombre = original.headers().name(i);
            if (nombre.equalsIgnoreCase("Connection") || nombre.equalsIgnoreCase("Upgrade")
                    || nombre.equalsIgnoreCase("HTTP2-Settings")) {
                continue;
            }
            limpias.add(nombre, original.headers().value(i));
        }
        return new IncomingRequest(original.method(), original.path(), original.rawQuery(),
                "HTTP/2.0", limpias, original.body(), original.remoteAddress(), context);
    }

    private void ajustesDeCabecera(String base64url) {
        try {
            byte[] crudos = java.util.Base64.getUrlDecoder().decode(base64url.trim());
            ajustes(0, 0, crudos, false);
        } catch (IllegalArgumentException | IOException malFormados) {
        }
    }

    static boolean pareceHttp2(java.io.PushbackInputStream entrada) throws IOException {
        byte[] ojeada = new byte[PREAMBULO.length];
        int leidos = 0;
        boolean coincide = true;
        while (coincide && leidos < PREAMBULO.length) {
            int b = entrada.read();
            if (b < 0) {
                break;
            }
            ojeada[leidos++] = (byte) b;
            coincide = ojeada[leidos - 1] == PREAMBULO[leidos - 1];
        }
        if (leidos > 0) {
            entrada.unread(ojeada, 0, leidos);
        }
        return coincide && leidos == PREAMBULO.length;
    }

    private void correr(IncomingRequest deUpgrade) throws IOException {
        escribirTrama(SETTINGS, 0, 0, ajustesPropios());

        byte[] esperado = new byte[PREAMBULO.length];
        try {
            leerDelTodo(esperado);
        } catch (java.io.EOFException cortado) {
            return;
        }
        if (!java.util.Arrays.equals(esperado, PREAMBULO)) {
            adios(ERROR_PROTOCOLO, "preámbulo inválido");
            return;
        }

        if (deUpgrade != null) {
            ultimoFlujoVisto = 1;
            Flujo f = new Flujo(1, ventanaInicialFlujo);
            flujos.put(1, f);
            f.finEntrada = true;
            lanzar(f, deUpgrade);
        }

        try {
            while (vivo) {
                if (!unaTrama()) {
                    break;
                }
            }
        } catch (ErrorConexion fallo) {
            adios(fallo.codigo, fallo.getMessage());
        } catch (Hpack.Rota rota) {
            adios(ERROR_COMPRESION, rota.getMessage());
        } catch (java.io.EOFException cortado) {
        } finally {
            vivo = false;
            for (Flujo f : flujos.values()) {
                f.cerrarEntrada();
            }
        }
    }

    private enum Estado { OCIOSO, ABIERTO, MEDIO_CERRADO, CERRADO }

    private Estado estadoDe(int idFlujo) {
        if (idFlujo > ultimoFlujoVisto) {
            return Estado.OCIOSO;
        }
        Flujo f = flujos.get(idFlujo);
        if (f == null) {
            return Estado.CERRADO;
        }
        return f.finEntrada ? Estado.MEDIO_CERRADO : Estado.ABIERTO;
    }

    private boolean unaTrama() throws IOException {
        byte[] cabecera = new byte[9];
        int leidos = 0;
        while (leidos < 9) {
            int n = in.read(cabecera, leidos, 9 - leidos);
            if (n < 0) {
                return leidos == 0 ? false : false;
            }
            leidos += n;
        }
        int largo = ((cabecera[0] & 0xFF) << 16) | ((cabecera[1] & 0xFF) << 8) | (cabecera[2] & 0xFF);
        int tipo = cabecera[3] & 0xFF;
        int banderas = cabecera[4] & 0xFF;
        int flujo = ((cabecera[5] & 0x7F) << 24) | ((cabecera[6] & 0xFF) << 16)
                | ((cabecera[7] & 0xFF) << 8) | (cabecera[8] & 0xFF);

        if (largo > TRAMA_MAXIMA) {
            throw new ErrorConexion(ERROR_TAMANO_TRAMA, "trama de " + largo + " bytes");
        }
        byte[] carga = new byte[largo];
        leerDelTodo(carga);

        switch (tipo) {
            case DATA -> datos(flujo, banderas, carga);
            case HEADERS -> cabeceras(flujo, banderas, carga);
            case PRIORITY -> {
                if (flujo == 0) {
                    throw new ErrorConexion(ERROR_PROTOCOLO, "PRIORITY sobre el flujo 0");
                }
                if (largo != 5) {
                    throw new ErrorConexion(ERROR_TAMANO_TRAMA, "PRIORITY mal medida");
                }
                if (dependeDeSiMismo(flujo, carga, 0)) {
                    escribirTrama(RST_STREAM, 0, flujo, deEntero(ERROR_PROTOCOLO));
                    return true;
                }
                controlDeMas();
            }
            case RST_STREAM -> {
                if (flujo == 0) {
                    throw new ErrorConexion(ERROR_PROTOCOLO, "RST_STREAM sobre el flujo 0");
                }
                if (largo != 4) {
                    throw new ErrorConexion(ERROR_TAMANO_TRAMA, "RST_STREAM mal medido");
                }
                if (estadoDe(flujo) == Estado.OCIOSO) {
                    throw new ErrorConexion(ERROR_PROTOCOLO, "RST_STREAM sobre un flujo ocioso");
                }
                Flujo f = flujos.remove(flujo);
                if (f != null) {
                    f.cerrarEntrada();
                    if (++anulados > maxAnulados) {
                        throw new ErrorConexion(ERROR_CALMA,
                                "demasiados flujos anulados sin responder");
                    }
                }
            }
            case SETTINGS -> ajustes(flujo, banderas, carga);
            case PUSH_PROMISE -> throw new ErrorConexion(ERROR_PROTOCOLO,
                    "un cliente no puede empujar");
            case PING -> {
                if (flujo != 0 || largo != 8) {
                    throw new ErrorConexion(ERROR_PROTOCOLO, "PING mal formado");
                }
                if ((banderas & RECONOCE) == 0) {
                    controlDeMas();
                    escribirTrama(PING, RECONOCE, 0, carga);
                }
            }
            case GOAWAY -> {
                vivo = false;
                cerrarConSuavidad();
                return false;
            }
            case WINDOW_UPDATE -> ventana(flujo, carga);
            case CONTINUATION -> throw new ErrorConexion(ERROR_PROTOCOLO,
                    "CONTINUATION sin HEADERS delante");
            default -> { }
        }
        return true;
    }

    private void cabeceras(int idFlujo, int banderas, byte[] carga) throws IOException {
        if (idFlujo == 0 || (idFlujo & 1) == 0) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "HEADERS sobre un flujo que no vale");
        }

        Flujo abierto = flujos.get(idFlujo);
        if (abierto != null && !abierto.finEntrada) {
            hpackIn.decodificar(leerBloqueDeCabeceras(idFlujo, banderas, carga));
            if ((banderas & FIN_FLUJO) == 0) {
                throw new ErrorConexion(ERROR_PROTOCOLO, "trailers sin fin de flujo");
            }
            abierto.finEntrada = true;
            abierto.cerrarEntrada();
            return;
        }

        if (idFlujo <= ultimoFlujoVisto) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "identificador de flujo hacia atrás");
        }
        if (activos() >= maxFlujos) {
            ultimoFlujoVisto = Math.max(ultimoFlujoVisto, idFlujo);
            escribirTrama(RST_STREAM, 0, idFlujo, deEntero(ERROR_RECHAZADO));
            return;
        }
        ultimoFlujoVisto = idFlujo;
        controlSeguidas = 0;

        List<Hpack.Campo> campos;
        try {
            campos = hpackIn.decodificar(leerBloqueDeCabeceras(idFlujo, banderas, carga));
        } catch (ErrorFlujo malFormada) {
            ultimoFlujoVisto = idFlujo;
            escribirTrama(RST_STREAM, 0, idFlujo, deEntero(ERROR_PROTOCOLO));
            return;
        }
        comprobarTamano(campos);
        Flujo flujo = new Flujo(idFlujo, ventanaInicialFlujo);
        flujos.put(idFlujo, flujo);
        if ((banderas & FIN_FLUJO) != 0) {
            flujo.finEntrada = true;
            flujo.cerrarEntrada();
        }
        for (Hpack.Campo c : campos) {
            if (c.nombre().equals("content-length")) {
                try {
                    flujo.declarado = Long.parseLong(c.valor().trim());
                } catch (NumberFormatException noEsUnNumero) {
                    escribirTrama(RST_STREAM, 0, idFlujo, deEntero(ERROR_PROTOCOLO));
                    flujos.remove(idFlujo);
                    return;
                }
            }
        }
        if (flujo.finEntrada && flujo.declarado > 0) {
            escribirTrama(RST_STREAM, 0, idFlujo, deEntero(ERROR_PROTOCOLO));
            flujos.remove(idFlujo);
            return;
        }

        IncomingRequest peticion;
        try {
            peticion = aPeticion(campos, flujo);
        } catch (ErrorFlujo malFormada) {
            escribirTrama(RST_STREAM, 0, idFlujo, deEntero(ERROR_PROTOCOLO));
            flujos.remove(idFlujo);
            return;
        }
        lanzar(flujo, peticion);
    }

    private byte[] leerBloqueDeCabeceras(int idFlujo, int banderas, byte[] carga)
            throws IOException {
        int pos = 0;
        int recorte = 0;
        if ((banderas & RELLENO) != 0) {
            recorte = carga[pos++] & 0xFF;
        }
        if ((banderas & PRIORIDAD) != 0) {
            if (dependeDeSiMismo(idFlujo, carga, pos)) {
                throw new ErrorFlujo("un flujo no puede depender de sí mismo");
            }
            pos += 5;
        }
        if (pos + recorte > carga.length) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "relleno mayor que la trama");
        }
        ByteArrayOutputStream bloque = new ByteArrayOutputStream();
        bloque.write(carga, pos, carga.length - pos - recorte);
        if (bloque.size() > maxBloqueCabeceras) {
            throw new ErrorConexion(ERROR_CALMA, "bloque de cabeceras desmedido");
        }

        boolean completo = (banderas & FIN_CABECERAS) != 0;
        int continuaciones = 0;
        while (!completo) {
            if (++continuaciones > MAX_CONTINUACIONES
                    || bloque.size() > maxBloqueCabeceras) {
                throw new ErrorConexion(ERROR_CALMA, "bloque de cabeceras desmedido");
            }
            byte[] cab = new byte[9];
            leerDelTodo(cab);
            int largo = ((cab[0] & 0xFF) << 16) | ((cab[1] & 0xFF) << 8) | (cab[2] & 0xFF);
            int tipo = cab[3] & 0xFF;
            int flags = cab[4] & 0xFF;
            int quien = ((cab[5] & 0x7F) << 24) | ((cab[6] & 0xFF) << 16)
                    | ((cab[7] & 0xFF) << 8) | (cab[8] & 0xFF);
            byte[] trozo = new byte[largo];
            leerDelTodo(trozo);
            if (tipo != CONTINUATION || quien != idFlujo) {
                throw new ErrorConexion(ERROR_PROTOCOLO, "se esperaba CONTINUATION");
            }
            bloque.write(trozo);
            if (bloque.size() > maxBloqueCabeceras) {
                throw new ErrorConexion(ERROR_CALMA, "bloque de cabeceras desmedido");
            }
            completo = (flags & FIN_CABECERAS) != 0;
        }
        return bloque.toByteArray();
    }

    private void comprobarTamano(List<Hpack.Campo> campos) {
        long total = 0;
        for (Hpack.Campo c : campos) {
            total += c.coste();
            if (total > maxListaCabeceras) {
                throw new ErrorConexion(ERROR_CALMA, "lista de cabeceras desmedida");
            }
        }
    }

    private IncomingRequest aPeticion(List<Hpack.Campo> campos, Flujo flujo) {
        String metodo = null;
        String destino = null;
        String autoridad = null;
        String esquema = null;
        Headers cabeceras = new Headers();
        boolean yaHuboNormal = false;

        for (Hpack.Campo c : campos) {
            String nombre = c.nombre();
            if (nombre.isEmpty() || !nombre.equals(nombre.toLowerCase(Locale.ROOT))) {
                throw new ErrorFlujo("nombre de campo inválido");
            }
            if (nombre.charAt(0) == ':') {
                if (yaHuboNormal) {
                    throw new ErrorFlujo("pseudo-campo después de un campo normal");
                }
                switch (nombre) {
                    case ":method" -> metodo = unaVez(metodo, c.valor());
                    case ":path" -> destino = unaVez(destino, c.valor());
                    case ":authority" -> autoridad = unaVez(autoridad, c.valor());
                    case ":scheme" -> esquema = unaVez(esquema, c.valor());
                    default -> throw new ErrorFlujo("pseudo-campo desconocido: " + nombre);
                }
                continue;
            }
            yaHuboNormal = true;
            if (nombre.equals("connection") || nombre.equals("keep-alive")
                    || nombre.equals("proxy-connection") || nombre.equals("transfer-encoding")
                    || nombre.equals("upgrade")) {
                throw new ErrorFlujo("cabecera específica de conexión: " + nombre);
            }
            if (nombre.equals("te") && !c.valor().equalsIgnoreCase("trailers")) {
                throw new ErrorFlujo("TE solo admite trailers");
            }
            cabeceras.add(nombre, c.valor());
        }

        if (metodo == null || esquema == null || destino == null || destino.isEmpty()) {
            throw new ErrorFlujo("faltan pseudo-campos obligatorios");
        }
        if (autoridad != null) {
            cabeceras.set("Host", autoridad);
        }

        int interrogante = destino.indexOf('?');
        String ruta = interrogante < 0 ? destino : destino.substring(0, interrogante);
        String consulta = interrogante < 0 ? null : destino.substring(interrogante + 1);

        HttpMethod verbo;
        try {
            verbo = HttpMethod.of(metodo);
        } catch (HttpException noSoportado) {
            throw new ErrorFlujo("método desconocido: " + metodo);
        }
        return new IncomingRequest(verbo, Url.decodePath(ruta), consulta, "HTTP/2.0",
                cabeceras, flujo.entrada, remoto, context);
    }

    private static String unaVez(String actual, String nuevo) {
        if (actual != null) {
            throw new ErrorFlujo("pseudo-campo repetido");
        }
        return nuevo;
    }

    private void lanzar(Flujo flujo, IncomingRequest peticion) {
        hilos.execute(() -> {
            Http2Respuesta respuesta = new Http2Respuesta(this, flujo, peticion);
            try {
                context.handler().handle(peticion, respuesta);
                respuesta.terminar();
            } catch (HttpException fallo) {
                respuesta.error(fallo.status(), fallo.getMessage());
            } catch (Exception fallo) {
                context.reporter().handler(peticion, fallo);
                respuesta.error(500, "error interno");
            } finally {
                flujo.cerrarEntrada();
                if (flujo.finEntrada) {
                    flujos.remove(flujo.id);
                } else {
                    flujo.respondido = true;
                    try {
                        podarRespondidos();
                    } catch (IOException seFue) {
                        flujos.remove(flujo.id);
                    }
                }
            }
        });
    }

    private void datos(int idFlujo, int banderas, byte[] carga) throws IOException {
        if (idFlujo == 0) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "DATA sobre el flujo 0");
        }
        switch (estadoDe(idFlujo)) {
            case OCIOSO -> throw new ErrorConexion(ERROR_PROTOCOLO, "DATA sobre un flujo ocioso");
            case MEDIO_CERRADO, CERRADO -> {
                if (carga.length > 0) {
                    escribirTrama(WINDOW_UPDATE, 0, 0, deEntero(carga.length));
                }
                escribirTrama(RST_STREAM, 0, idFlujo, deEntero(ERROR_FLUJO_CERRADO));
                flujos.remove(idFlujo);
                return;
            }
            default -> { }
        }
        int pos = 0;
        int recorte = 0;
        if ((banderas & RELLENO) != 0) {
            recorte = carga[pos++] & 0xFF;
        }
        if (pos + recorte > carga.length) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "relleno mayor que la trama");
        }
        if (carga.length > 0) {
            escribirTrama(WINDOW_UPDATE, 0, 0, deEntero(carga.length));
        }
        Flujo f = flujos.get(idFlujo);
        if (f == null) {
            return;
        }
        int utiles = carga.length - pos - recorte;
        long total = f.recibido.addAndGet(utiles);
        if (f.declarado >= 0 && (total > f.declarado
                || ((banderas & FIN_FLUJO) != 0 && total != f.declarado))) {
            escribirTrama(RST_STREAM, 0, idFlujo, deEntero(ERROR_PROTOCOLO));
            flujos.remove(idFlujo);
            f.cerrarEntrada();
            return;
        }
        f.entrada.aportar(carga, pos, utiles);
        if (carga.length > 0) {
            escribirTrama(WINDOW_UPDATE, 0, idFlujo, deEntero(carga.length));
        }
        if ((banderas & FIN_FLUJO) != 0) {
            f.finEntrada = true;
            f.cerrarEntrada();
            if (f.respondido) {
                flujos.remove(idFlujo);
            }
        }
    }

    private void ajustes(int flujo, int banderas, byte[] carga) throws IOException {
        ajustes(flujo, banderas, carga, true);
    }

    private void ajustes(int flujo, int banderas, byte[] carga, boolean reconocer)
            throws IOException {
        if (flujo != 0) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "SETTINGS sobre un flujo");
        }
        if ((banderas & RECONOCE) != 0) {
            if (carga.length != 0) {
                throw new ErrorConexion(ERROR_TAMANO_TRAMA, "SETTINGS con ACK y carga");
            }
            return;
        }
        controlDeMas();
        if (carga.length % 6 != 0) {
            throw new ErrorConexion(ERROR_TAMANO_TRAMA, "SETTINGS de tamaño imposible");
        }
        for (int i = 0; i < carga.length; i += 6) {
            int clave = ((carga[i] & 0xFF) << 8) | (carga[i + 1] & 0xFF);
            long valor = ((long) (carga[i + 2] & 0xFF) << 24) | ((carga[i + 3] & 0xFF) << 16)
                    | ((carga[i + 4] & 0xFF) << 8) | (carga[i + 5] & 0xFF);
            switch (clave) {
                case 0x4 -> {
                    if (valor > Integer.MAX_VALUE) {
                        throw new ErrorConexion(ERROR_CONTROL_FLUJO, "ventana inicial imposible");
                    }
                    int delta = (int) valor - ventanaInicialFlujo;
                    ventanaInicialFlujo = (int) valor;
                    for (Flujo f : flujos.values()) {
                        f.ventana.addAndGet(delta);
                    }
                }
                case 0x5 -> {
                    if (valor < 16_384 || valor > 16_777_215) {
                        throw new ErrorConexion(ERROR_PROTOCOLO, "tamaño de trama fuera de rango");
                    }
                    tramaMaxima = (int) Math.min(valor, TRAMA_MAXIMA);
                }
                case 0x2 -> {
                    if (valor > 1) {
                        throw new ErrorConexion(ERROR_PROTOCOLO, "ENABLE_PUSH inválido");
                    }
                }
                default -> { }
            }
        }
        if (reconocer) {
            escribirTrama(SETTINGS, RECONOCE, 0, new byte[0]);
        }
    }

    private void ventana(int flujo, byte[] carga) throws IOException {
        if (carga.length != 4) {
            throw new ErrorConexion(ERROR_TAMANO_TRAMA, "WINDOW_UPDATE mal formado");
        }
        int incremento = (((carga[0] & 0x7F) << 24) | ((carga[1] & 0xFF) << 16)
                | ((carga[2] & 0xFF) << 8) | (carga[3] & 0xFF));
        if (incremento == 0) {
            if (flujo == 0) {
                throw new ErrorConexion(ERROR_PROTOCOLO, "incremento de ventana cero");
            }
            escribirTrama(RST_STREAM, 0, flujo, deEntero(ERROR_PROTOCOLO));
            return;
        }
        if (flujo != 0 && estadoDe(flujo) == Estado.OCIOSO) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "WINDOW_UPDATE sobre un flujo ocioso");
        }
        controlDeMas();
        if (flujo == 0) {
            if (ventanaSalida.addAndGet(incremento) > 0x7FFFFFFFL) {
                throw new ErrorConexion(ERROR_CONTROL_FLUJO, "ventana desbordada");
            }
            synchronized (ventanaSalida) {
                ventanaSalida.notifyAll();
            }
        } else {
            Flujo f = flujos.get(flujo);
            if (f != null) {
                if ((long) f.ventana.get() + incremento > 0x7FFFFFFFL) {
                    escribirTrama(RST_STREAM, 0, flujo, deEntero(ERROR_CONTROL_FLUJO));
                    flujos.remove(flujo);
                    return;
                }
                f.ventana.addAndGet(incremento);
                synchronized (f.ventana) {
                    f.ventana.notifyAll();
                }
            }
        }
    }

    private byte[] ajustesPropios() {
        return new byte[] {
                0, 0x3, (byte) (maxFlujos >>> 24), (byte) (maxFlujos >>> 16),
                        (byte) (maxFlujos >>> 8), (byte) maxFlujos,
                0, 0x4, 0, 0x1, 0, 0,
                0, 0x5, 0, 0x40, 0, 0,
                0, 0x2, 0, 0, 0, 0,
                0, 0x6, (byte) (maxListaCabeceras >>> 24), (byte) (maxListaCabeceras >>> 16),
                        (byte) (maxListaCabeceras >>> 8), (byte) maxListaCabeceras,
        };
    }

    void escribirTrama(int tipo, int banderas, int flujo, byte[] carga) throws IOException {
        synchronized (candadoEscritura) {
            out.write(new byte[] {
                    (byte) (carga.length >>> 16), (byte) (carga.length >>> 8), (byte) carga.length,
                    (byte) tipo, (byte) banderas,
                    (byte) (flujo >>> 24), (byte) (flujo >>> 16), (byte) (flujo >>> 8), (byte) flujo,
            });
            out.write(carga);
            out.flush();
        }
    }

    void mandarCabeceras(int flujo, List<Hpack.Campo> campos, boolean fin) throws IOException {
        byte[] bloque = hpackOut.codificar(campos);
        int banderas = FIN_CABECERAS | (fin ? FIN_FLUJO : 0);
        if (bloque.length <= tramaMaxima) {
            escribirTrama(HEADERS, banderas, flujo, bloque);
            return;
        }
        synchronized (candadoEscritura) {
            escribirTrama(HEADERS, fin ? FIN_FLUJO : 0, flujo,
                    java.util.Arrays.copyOfRange(bloque, 0, tramaMaxima));
            int pos = tramaMaxima;
            while (pos < bloque.length) {
                int hasta = Math.min(pos + tramaMaxima, bloque.length);
                escribirTrama(CONTINUATION, hasta == bloque.length ? FIN_CABECERAS : 0, flujo,
                        java.util.Arrays.copyOfRange(bloque, pos, hasta));
                pos = hasta;
            }
        }
    }

    void mandarDatos(Flujo flujo, byte[] cuerpo, boolean fin) throws IOException {
        if (cuerpo.length == 0) {
            if (fin) {
                escribirTrama(DATA, FIN_FLUJO, flujo.id, new byte[0]);
            }
            return;
        }
        int pos = 0;
        while (pos < cuerpo.length) {
            int quedan = cuerpo.length - pos;
            int trozo = esperarHueco(flujo, Math.min(quedan, tramaMaxima));
            boolean ultimo = fin && pos + trozo >= cuerpo.length;
            escribirTrama(DATA, ultimo ? FIN_FLUJO : 0, flujo.id,
                    java.util.Arrays.copyOfRange(cuerpo, pos, pos + trozo));
            pos += trozo;
        }
    }

    private int esperarHueco(Flujo flujo, int deseado) throws IOException {
        while (vivo) {
            long conexion = ventanaSalida.get();
            int delFlujo = flujo.ventana.get();
            int posible = (int) Math.min(Math.min(conexion, delFlujo), deseado);
            if (posible > 0) {
                ventanaSalida.addAndGet(-posible);
                flujo.ventana.addAndGet(-posible);
                return posible;
            }
            try {
                Object cerrojo = conexion <= 0 ? ventanaSalida : flujo.ventana;
                synchronized (cerrojo) {
                    cerrojo.wait(5_000);
                }
            } catch (InterruptedException cortado) {
                Thread.currentThread().interrupt();
                throw new IOException("interrumpido esperando ventana");
            }
        }
        throw new IOException("conexión cerrada");
    }

    private void adios(int codigo, String motivo) {
        try {
            byte[] carga = new byte[8 + (motivo == null ? 0 : motivo.length())];
            carga[0] = (byte) (ultimoFlujoVisto >>> 24);
            carga[1] = (byte) (ultimoFlujoVisto >>> 16);
            carga[2] = (byte) (ultimoFlujoVisto >>> 8);
            carga[3] = (byte) ultimoFlujoVisto;
            carga[4] = (byte) (codigo >>> 24);
            carga[5] = (byte) (codigo >>> 16);
            carga[6] = (byte) (codigo >>> 8);
            carga[7] = (byte) codigo;
            if (motivo != null) {
                byte[] texto = motivo.getBytes(StandardCharsets.ISO_8859_1);
                System.arraycopy(texto, 0, carga, 8, texto.length);
            }
            escribirTrama(GOAWAY, 0, 0, carga);
        } catch (IOException yaNoSePuede) {
        }
        vivo = false;
        cerrarConSuavidad();
    }

    private int activos() {
        int n = 0;
        for (Flujo f : flujos.values()) {
            if (!f.respondido) {
                n++;
            }
        }
        return n;
    }

    private void podarRespondidos() throws IOException {
        while (flujos.size() > maxFlujos * 2) {
            int viejo = Integer.MAX_VALUE;
            for (Flujo f : flujos.values()) {
                if (f.respondido && f.id < viejo) {
                    viejo = f.id;
                }
            }
            if (viejo == Integer.MAX_VALUE || flujos.remove(viejo) == null) {
                return;
            }
            escribirTrama(RST_STREAM, 0, viejo, deEntero(ERROR_NINGUNO));
        }
    }

    private void controlDeMas() {
        if (++controlSeguidas > maxControlSeguidas) {
            throw new ErrorConexion(ERROR_CALMA, "demasiadas tramas de control sin pedir nada");
        }
    }

    private static boolean dependeDeSiMismo(int flujo, byte[] carga, int desde) {
        if (carga.length < desde + 5) {
            return false;
        }
        int padre = ((carga[desde] & 0x7F) << 24) | ((carga[desde + 1] & 0xFF) << 16)
                | ((carga[desde + 2] & 0xFF) << 8) | (carga[desde + 3] & 0xFF);
        return padre == flujo;
    }

    private void cerrarConSuavidad() {
        try {
            out.flush();
            socketCrudo.shutdownOutput();
        } catch (IOException yaEstaba) {
            return;
        }
        try {
            socketCrudo.setSoTimeout(1_000);
            byte[] resto = new byte[4096];
            long tope = 0;
            while (in.read(resto) > 0 && (tope += resto.length) < 1 << 20) {
            }
        } catch (IOException seFue) {
        }
    }

    private static byte[] deEntero(int valor) {
        return new byte[] {
                (byte) (valor >>> 24), (byte) (valor >>> 16), (byte) (valor >>> 8), (byte) valor };
    }

    private void leerDelTodo(byte[] destino) throws IOException {
        int leidos = 0;
        while (leidos < destino.length) {
            int n = in.read(destino, leidos, destino.length - leidos);
            if (n < 0) {
                throw new java.io.EOFException();
            }
            leidos += n;
        }
    }

    static final class Flujo {

        final int id;
        final Tuberia entrada = new Tuberia();
        final java.util.concurrent.atomic.AtomicInteger ventana;
        volatile boolean finEntrada;

        volatile boolean respondido;

        volatile long declarado = -1;

        final java.util.concurrent.atomic.AtomicLong recibido =
                new java.util.concurrent.atomic.AtomicLong();

        Flujo(int id, int ventanaInicial) {
            this.id = id;
            this.ventana = new java.util.concurrent.atomic.AtomicInteger(ventanaInicial);
        }

        void cerrarEntrada() {
            entrada.cerrar();
        }
    }

    static final class Tuberia extends InputStream {

        private final ArrayList<byte[]> trozos = new ArrayList<>();
        private int indice;
        private int desplazamiento;
        private boolean cerrada;

        synchronized void aportar(byte[] datos, int desde, int largo) {
            if (largo <= 0 || cerrada) {
                return;
            }
            trozos.add(java.util.Arrays.copyOfRange(datos, desde, desde + largo));
            notifyAll();
        }

        synchronized void cerrar() {
            cerrada = true;
            notifyAll();
        }

        @Override
        public synchronized int read() throws IOException {
            byte[] uno = new byte[1];
            int n = read(uno, 0, 1);
            return n < 0 ? -1 : uno[0] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] destino, int desde, int largo) throws IOException {
            while (indice >= trozos.size()) {
                if (cerrada) {
                    return -1;
                }
                try {
                    wait(30_000);
                } catch (InterruptedException cortado) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrumpido leyendo el cuerpo");
                }
                if (indice >= trozos.size() && cerrada) {
                    return -1;
                }
            }
            byte[] trozo = trozos.get(indice);
            int copiado = Math.min(largo, trozo.length - desplazamiento);
            System.arraycopy(trozo, desplazamiento, destino, desde, copiado);
            desplazamiento += copiado;
            if (desplazamiento >= trozo.length) {
                trozos.set(indice, null);
                indice++;
                desplazamiento = 0;
            }
            return copiado;
        }
    }

    static final class ErrorConexion extends RuntimeException {

        final int codigo;

        ErrorConexion(int codigo, String mensaje) {
            super(mensaje);
            this.codigo = codigo;
        }
    }

    static final class ErrorFlujo extends RuntimeException {

        ErrorFlujo(String mensaje) {
            super(mensaje);
        }
    }
}
