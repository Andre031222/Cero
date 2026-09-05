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

/**
 * HTTP/2 en claro (h2c) sobre el mismo servidor y el mismo {@link Handler}.
 *
 * <p>Una aplicación no se entera: recibe el mismo {@link Request} y el mismo {@link Response}
 * que en HTTP/1.1. Lo que cambia debajo es todo — el protocolo deja de ser texto y pasa a ser
 * tramas binarias multiplexadas sobre una conexión.
 *
 * <h2>Qué está y qué no</h2>
 *
 * <p>Está: capa de tramas, HPACK completo ({@link Hpack}), flujos concurrentes, control de flujo
 * por conexión y por flujo, SETTINGS negociados, PING, RST_STREAM y GOAWAY ordenado. Se entra por
 * las dos puertas de h2c: conocimiento previo —el cliente manda el preámbulo directamente— y
 * {@code Upgrade: h2c} desde una petición HTTP/1.1.
 *
 * <p>No está, y se dice: <b>PUSH_PROMISE</b>, que está en desuso y los navegadores ya no lo
 * usan; se anuncia deshabilitado en SETTINGS, que es lo que manda el RFC. <b>PRIORITY</b> se lee
 * y se descarta, que es lo que permite el RFC 9113 tras deprecar el esquema de prioridades.
 * Y <b>h2 sobre TLS con ALPN</b>: en claro funciona, cifrado todavía no.
 *
 * <p>Guía: https://cero.ginit.dev/guia#http2
 */
final class Http2 {

    /** RFC 9113 §3.4. Lo que manda un cliente que ya sabe que hablas h2. */
    static final byte[] PREAMBULO =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    // Tipos de trama (§6).
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

    // Banderas.
    private static final int FIN_FLUJO = 0x1;
    private static final int FIN_CABECERAS = 0x4;
    private static final int RECONOCE = 0x1;      // ACK en SETTINGS y PING
    private static final int RELLENO = 0x8;
    private static final int PRIORIDAD = 0x20;

    // Códigos de error (§7).
    private static final int SIN_ERROR = 0x0;
    private static final int ERROR_PROTOCOLO = 0x1;
    private static final int ERROR_INTERNO = 0x2;
    private static final int ERROR_CONTROL_FLUJO = 0x3;
    private static final int ERROR_TAMANO_TRAMA = 0x6;
    private static final int ERROR_RECHAZADO = 0x7;
    private static final int ERROR_ANULADO = 0x8;
    private static final int ERROR_FLUJO_CERRADO = 0x5;
    private static final int ERROR_COMPRESION = 0x9;
    private static final int ERROR_CALMA = 0xb;   // ENHANCE_YOUR_CALM

    private static final int TRAMA_MAXIMA = 16_384;
    private static final int VENTANA_INICIAL = 65_535;


    // ─── topes contra las inundaciones conocidas de HTTP/2 ───────────────────────────────────
    //
    // Los cuatro salen de ataques publicados, no de suponer. El protocolo permite que un cliente
    // pida trabajo al servidor sin coste propio, y sin topes eso es una negación de servicio con
    // una sola conexión.

    /**
     * Cuántas CONTINUATION se admiten seguidas.
     *
     * <p>No es configurable como las demás porque no es una cuestión de capacidad: un bloque de
     * cabeceras legítimo cabe en unas pocas tramas, y sesenta y cuatro ya es holgado. El tope que
     * sí conviene poder subir es el de bytes, y ese está en {@link ServerOptions}.
     */
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

    /** Ventana de la conexión entera, en bytes que el cliente nos deja mandar. */
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

    /** Flujos que el cliente anuló antes de que se respondieran. Ver {@link #maxAnulados}. */
    private int anulados;

    /** Tramas de control seguidas sin que el cliente abra un flujo. */
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

    /**
     * Atiende un socket que habla h2c por conocimiento previo.
     *
     * <p>El grupo de hilos es de la conexión y muere con ella: cada flujo va en su propio hilo
     * virtual, y al salir se espera a que terminen. Sin esa espera, cerrar el socket cortaría
     * respuestas a medio escribir.
     */
    static void servir(Socket socket, InputStream in, OutputStream out, ServerContext context,
                       String remoto) throws IOException {
        try (ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            new Http2(socket, in, out, context, remoto, hilos).correr(null);
        }
    }

    /**
     * ¿Esta petición pide cambiar a HTTP/2?
     *
     * <p>RFC 9113 §3.2: hacen falta las tres a la vez —{@code Upgrade: h2c},
     * {@code HTTP2-Settings} y las dos nombradas en {@code Connection}—. Con menos no es una
     * petición de cambio, es una cabecera suelta que hay que ignorar.
     */
    static boolean pidenUpgrade(IncomingRequest peticion) {
        String upgrade = peticion.header("Upgrade");
        return upgrade != null && upgrade.trim().equalsIgnoreCase("h2c")
                && peticion.header("HTTP2-Settings") != null
                && peticion.headers().contains("Connection", "Upgrade")
                && peticion.headers().contains("Connection", "HTTP2-Settings");
    }

    /**
     * Contesta 101 y sigue hablando HTTP/2 sobre el mismo socket.
     *
     * <p>La petición que pidió el cambio no se pierde: se atiende como flujo 1, que es lo que
     * manda el RFC, y ese identificador queda gastado — el cliente empieza a numerar por el 3.
     */
    static void aceptarUpgrade(Socket socket, InputStream in, OutputStream out,
                               ServerContext context, String remoto, IncomingRequest peticion)
            throws IOException {
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: h2c\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        try (ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2 conexion = new Http2(socket, in, out, context, remoto, hilos);
            // Los SETTINGS del cliente viajan en base64url dentro de la cabecera, no como trama.
            // Se aplican antes del preámbulo porque de ellos depende la ventana con la que nace
            // el flujo 1, que se atiende inmediatamente.
            conexion.ajustesDeCabecera(peticion.header("HTTP2-Settings"));
            conexion.correr(comoHttp2(peticion, context));
        }
    }

    /**
     * La misma petición, pero declarándose HTTP/2.
     *
     * <p>Entró como HTTP/1.1 y por eso lo decía, pero se atiende como flujo 1 y se responde con
     * tramas. Dejar que un controlador lea «HTTP/1.1» ahí le haría tomar decisiones sobre un
     * protocolo que ya no es el que hay debajo — empezando por creer que puede pedir un
     * {@code switchProtocols()}.
     *
     * <p>Se quitan además las tres cabeceras del propio cambio: en HTTP/2 no existen, y dejarlas
     * visibles sería enseñar a la aplicación algo que el protocolo prohíbe.
     */
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

    /** Aplica los SETTINGS que vinieron en la cabecera del Upgrade. */
    private void ajustesDeCabecera(String base64url) {
        try {
            byte[] crudos = java.util.Base64.getUrlDecoder().decode(base64url.trim());
            ajustes(0, 0, crudos, false);
        } catch (IllegalArgumentException | IOException malFormados) {
            // §3.2: una cabecera que no se puede leer se trata como si no estuviera. El cambio
            // ya está aceptado y deshacerlo no es una opción, así que se sigue con los valores
            // por defecto, que es lo que habría pasado sin la cabecera.
        }
    }

    /**
     * ¿Empieza esto con el preámbulo de h2c? Se mira sin consumir.
     *
     * <p>Byte a byte, y se abandona en cuanto uno no coincide. No es una optimización: leer los
     * 24 de golpe bloquea contra un cliente que manda menos y se queda esperando respuesta —una
     * petición HTTP/1.1 corta, o una malformada— y esa conexión no avanzaría hasta el tiempo de
     * espera. Una petición normal diverge en el primer o segundo byte: «GET» no es «PRI».
     */
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

    // ─── el bucle ────────────────────────────────────────────────────────────────────────────

    private void correr(IncomingRequest deUpgrade) throws IOException {
        // Los SETTINGS van primero, antes incluso de leer el preámbulo: §3.4 dice que son la
        // primera trama que manda el servidor, y un cliente puede estar esperándolos.
        escribirTrama(SETTINGS, 0, 0, ajustesPropios());

        byte[] esperado = new byte[PREAMBULO.length];
        try {
            leerDelTodo(esperado);
        } catch (java.io.EOFException cortado) {
            return;
        }
        if (!java.util.Arrays.equals(esperado, PREAMBULO)) {
            // Se despide antes de colgar: cerrar en seco deja al otro lado sin saber por qué.
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
            // Un fallo de HPACK desincroniza la tabla: lo que venga después ya no significa
            // nada. Por eso es de conexión y no de flujo.
            adios(ERROR_COMPRESION, rota.getMessage());
        } catch (java.io.EOFException cortado) {
            // El cliente se fue sin despedirse. No es un error que reportar.
        } finally {
            vivo = false;
            for (Flujo f : flujos.values()) {
                f.cerrarEntrada();
            }
        }
    }

    /**
     * El estado de un flujo según el RFC 9113 §5.1, deducido de lo que ya se sabe.
     *
     * <p>No hace falta un mapa de flujos cerrados: cualquier identificador mayor que el último
     * visto está <b>ocioso</b> —nunca se abrió— y cualquiera menor o igual que ya no está en el
     * mapa de abiertos está <b>cerrado</b>. Guardar los cerrados sería memoria que crece con cada
     * petición y que un cliente podría hacer crecer a voluntad.
     *
     * <p>La distinción importa porque el RFC pide errores distintos: una trama sobre un flujo
     * ocioso rompe la conexión —el cliente se está inventando el estado— mientras que sobre uno
     * cerrado solo corta ese flujo, porque puede ser una carrera legítima con un RST_STREAM que
     * se cruzó por el cable.
     */
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

    /** @return false cuando el cliente cerró */
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
                // El RFC 9113 deprecó el esquema de prioridades y permite ignorar lo que dice.
                // Ignorar el contenido no es ignorar la trama: su forma sigue siendo obligatoria,
                // y una mal formada es un error como cualquier otra.
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
                    // Anular un flujo lo saca del tope de concurrencia al instante, así que
                    // abrir-y-anular en bucle deja pedir trabajo sin límite mientras se aparenta
                    // respetar las reglas. Es el CVE-2023-44487. Anular es legítimo; hacerlo cien
                    // veces sin dejar responder ninguna, no.
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
                // Se acepta con cualquier código, incluidos los que no se conocen: §7 dice que
                // un código desconocido no invalida la trama. Y se cierra por las buenas —media
                // conexión primero— para que lo que ya se escribió llegue en vez de perderse en
                // un reinicio de TCP.
                vivo = false;
                cerrarConSuavidad();
                return false;
            }
            case WINDOW_UPDATE -> ventana(flujo, carga);
            case CONTINUATION -> throw new ErrorConexion(ERROR_PROTOCOLO,
                    "CONTINUATION sin HEADERS delante");
            default -> { /* §4.1: un tipo desconocido se descarta sin más. */ }
        }
        return true;
    }

    // ─── cabeceras y arranque de la petición ─────────────────────────────────────────────────

    private void cabeceras(int idFlujo, int banderas, byte[] carga) throws IOException {
        if (idFlujo == 0 || (idFlujo & 1) == 0) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "HEADERS sobre un flujo que no vale");
        }

        // Un HEADERS sobre un flujo que ya está abierto son los trailers: las cabeceras que van
        // detrás del cuerpo. Se descartan —igual que en HTTP/1.1, que también los salta— pero
        // hay que DECODIFICARLOS igual. La tabla dinámica de HPACK es un estado compartido que
        // avanza con cada bloque: saltarse uno la descoloca, y a partir de ahí todos los índices
        // de esa conexión significan otra cosa. Tirar los bytes sin leerlos rompe la conexión
        // entera unas cuantas peticiones más tarde, lejos de la causa.
        Flujo abierto = flujos.get(idFlujo);
        if (abierto != null && !abierto.finEntrada) {
            hpackIn.decodificar(leerBloqueDeCabeceras(idFlujo, banderas, carga));
            if ((banderas & FIN_FLUJO) == 0) {
                // §8.1: los trailers son el último bloque del flujo. Sin FIN_FLUJO no son
                // trailers, son un segundo bloque de cabeceras, que no existe.
                throw new ErrorConexion(ERROR_PROTOCOLO, "trailers sin fin de flujo");
            }
            abierto.finEntrada = true;
            abierto.cerrarEntrada();
            return;
        }

        if (idFlujo <= ultimoFlujoVisto) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "identificador de flujo hacia atrás");
        }
        if (flujos.size() >= maxFlujos) {
            // REFUSED_STREAM y no ENHANCE_YOUR_CALM: le dice al cliente que puede reintentar
            // ese flujo más tarde, que es exactamente lo que pasa. «Calma» significaría que se
            // está portando mal, y pedir tantos como se anunciaron no es portarse mal.
            ultimoFlujoVisto = Math.max(ultimoFlujoVisto, idFlujo);
            escribirTrama(RST_STREAM, 0, idFlujo, deEntero(ERROR_RECHAZADO));
            return;
        }
        ultimoFlujoVisto = idFlujo;
        controlSeguidas = 0;   // abrir un flujo es progreso: la cuenta de control se reinicia

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
        // Sin cuerpo declarado pero con content-length distinto de cero: tampoco cuadra.
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

    /**
     * Junta el bloque de cabeceras: quita el relleno y la prioridad, y sigue por CONTINUATION.
     *
     * <p>Hasta que llegue FIN_CABECERAS no puede intercalarse ninguna otra trama, ni siquiera de
     * otro flujo: para HPACK el bloque es indivisible, y algo en medio lo descolocaría.
     */
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
            // Sin estos dos topes, un cliente manda HEADERS y luego CONTINUATION sin fin: el
            // bloque crece en memoria hasta agotarla, y nada en el protocolo lo impide. Es el
            // CVE-2024-27316, que en 2024 afectó a media docena de servidores a la vez.
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

    /**
     * El tamaño de la lista de cabeceras ya descomprimida.
     *
     * <p>HPACK comprime, y eso significa que unos pocos octetos en el cable pueden convertirse en
     * muchos megabytes de cabeceras: basta con referenciar mil veces una entrada grande de la
     * tabla. Limitar el bloque comprimido no basta — hay que mirar lo que sale.
     *
     * <p>Se cuenta como manda el RFC 7541 §4.1: nombre + valor + 32 por campo.
     */
    private void comprobarTamano(List<Hpack.Campo> campos) {
        long total = 0;
        for (Hpack.Campo c : campos) {
            total += c.coste();
            if (total > maxListaCabeceras) {
                throw new ErrorConexion(ERROR_CALMA, "lista de cabeceras desmedida");
            }
        }
    }

    /** Los pseudo-campos de h2 se convierten en la petición que el resto del framework entiende. */
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
                // §8.2.1: los nombres van en minúscula. En mayúscula es malformada, no un
                // detalle de estilo: dos intermediarios podrían verlas como campos distintos.
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
                // §8.2.2: las cabeceras de conexión de HTTP/1.1 no existen aquí.
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
            // En HTTP/1.1 esto sale como 501 porque hay una línea de estado donde ponerlo. En
            // h2 llega antes de que exista el flujo como tal, así que se corta el flujo y la
            // conexión sigue: las demás peticiones de ese cliente no tienen la culpa.
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

    /** Cada flujo se atiende en su propio hilo virtual: eso es la multiplexación. */
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
                flujos.remove(flujo.id);
            }
        });
    }

    // ─── datos, ajustes y ventanas ───────────────────────────────────────────────────────────

    private void datos(int idFlujo, int banderas, byte[] carga) throws IOException {
        if (idFlujo == 0) {
            throw new ErrorConexion(ERROR_PROTOCOLO, "DATA sobre el flujo 0");
        }
        switch (estadoDe(idFlujo)) {
            case OCIOSO -> throw new ErrorConexion(ERROR_PROTOCOLO, "DATA sobre un flujo ocioso");
            case MEDIO_CERRADO, CERRADO -> {
                // El cliente ya dijo que había terminado de mandar, o el flujo ya no existe.
                // La ventana se devuelve igual: esos octetos ya contaron contra la conexión y,
                // si no se reponen, el cliente acaba bloqueado sin haber hecho nada mal.
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
        // La ventana se repone siempre, incluso si el flujo ya no existe: el cliente contó esos
        // bytes contra la conexión y si no se los devolvemos acabará bloqueado para siempre.
        if (carga.length > 0) {
            escribirTrama(WINDOW_UPDATE, 0, 0, deEntero(carga.length));
        }
        Flujo f = flujos.get(idFlujo);
        if (f == null) {
            return;
        }
        int utiles = carga.length - pos - recorte;
        // §8.1.2.6: si se declaró content-length, tiene que cuadrar con lo que llega. Un
        // desajuste es la puerta de entrada al contrabando de peticiones cuando hay un proxy
        // delante: dos intermediarios leen dos peticiones distintas de los mismos octetos.
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
        }
    }

    private void ajustes(int flujo, int banderas, byte[] carga) throws IOException {
        ajustes(flujo, banderas, carga, true);
    }

    /**
     * @param reconocer falso cuando los SETTINGS no vinieron en una trama sino en la cabecera del
     *                  Upgrade: ahí no hay nada que reconocer, y mandar un ACK antes del
     *                  preámbulo confundiría al cliente
     */
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
                case 0x4 -> {                                   // INITIAL_WINDOW_SIZE
                    if (valor > Integer.MAX_VALUE) {
                        throw new ErrorConexion(ERROR_CONTROL_FLUJO, "ventana inicial imposible");
                    }
                    int delta = (int) valor - ventanaInicialFlujo;
                    ventanaInicialFlujo = (int) valor;
                    for (Flujo f : flujos.values()) {
                        f.ventana.addAndGet(delta);
                    }
                }
                case 0x5 -> {                                   // MAX_FRAME_SIZE
                    if (valor < 16_384 || valor > 16_777_215) {
                        throw new ErrorConexion(ERROR_PROTOCOLO, "tamaño de trama fuera de rango");
                    }
                    tramaMaxima = (int) Math.min(valor, TRAMA_MAXIMA);
                }
                case 0x2 -> {                                   // ENABLE_PUSH
                    if (valor > 1) {
                        throw new ErrorConexion(ERROR_PROTOCOLO, "ENABLE_PUSH inválido");
                    }
                }
                default -> { /* §6.5.2: lo que no se conoce se ignora. */ }
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
                // La ventana de un flujo no puede pasar de 2^31-1. Es error de flujo y no de
                // conexión: solo ese flujo queda en un estado imposible.
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
        // MAX_CONCURRENT_STREAMS, INITIAL_WINDOW_SIZE, MAX_FRAME_SIZE y push desactivado.
        return new byte[] {
                0, 0x3, (byte) (maxFlujos >>> 24), (byte) (maxFlujos >>> 16),
                        (byte) (maxFlujos >>> 8), (byte) maxFlujos,
                0, 0x4, 0, 0x1, 0, 0,
                0, 0x5, 0, 0x40, 0, 0,
                0, 0x2, 0, 0, 0, 0,
                // MAX_HEADER_LIST_SIZE: se anuncia además de hacerse cumplir, para que un cliente
                // educado no llegue siquiera a mandar algo que se le va a rechazar.
                0, 0x6, (byte) (maxListaCabeceras >>> 24), (byte) (maxListaCabeceras >>> 16),
                        (byte) (maxListaCabeceras >>> 8), (byte) maxListaCabeceras,
        };
    }

    // ─── escritura ───────────────────────────────────────────────────────────────────────────

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
        // Bloque grande: HEADERS sin FIN_CABECERAS y el resto en CONTINUATION. Entre medias no
        // puede colarse nada, y por eso todo esto va bajo el mismo candado.
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

    /**
     * Manda el cuerpo respetando las dos ventanas: la del flujo y la de la conexión.
     *
     * <p>El caso vacío se resuelve antes del bucle y no dentro. Con cero bytes, el hueco que se
     * pide también es cero, y esperar a que quepan cero bytes es esperar para siempre: el bucle
     * no salía nunca. No se notaba porque hasta que `stream()` empezó a mandar tramas según se
     * escribe, aquí no llegaba nunca un cuerpo vacío.
     */
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

    /** Espera a que quepa algo en las dos ventanas y reserva lo que quepa. */
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
            // El socket ya estaba roto: no hay a quién despedirse.
        }
        vivo = false;
        // Sin esto el GOAWAY que se acaba de escribir se pierde en el RST del cierre, y el
        // cliente ve una conexión reiniciada en vez de un motivo.
        cerrarConSuavidad();
    }

    /**
     * Una trama de control más sin haber abierto ningún flujo.
     *
     * <p>PING, SETTINGS, WINDOW_UPDATE y PRIORITY obligan al servidor a hacer algo —contestar,
     * recalcular ventanas— y no cuestan casi nada al cliente. Mandadas en bucle son una
     * amplificación: el que ataca escribe ocho octetos y el que recibe escribe otros tantos y
     * gasta un despertar de hilo. Contarlas solo entre flujos deja pasar el uso normal, donde
     * siempre hay peticiones de por medio.
     */
    private void controlDeMas() {
        if (++controlSeguidas > maxControlSeguidas) {
            throw new ErrorConexion(ERROR_CALMA, "demasiadas tramas de control sin pedir nada");
        }
    }

    /** ¿Este bloque de prioridad dice que el flujo depende de sí mismo? §5.3.1 lo prohíbe. */
    private static boolean dependeDeSiMismo(int flujo, byte[] carga, int desde) {
        if (carga.length < desde + 5) {
            return false;
        }
        int padre = ((carga[desde] & 0x7F) << 24) | ((carga[desde + 1] & 0xFF) << 16)
                | ((carga[desde + 2] & 0xFF) << 8) | (carga[desde + 3] & 0xFF);
        return padre == flujo;
    }

    /**
     * Cierra la conexión sin perder lo último que se escribió.
     *
     * <p>Cerrar un socket que todavía tiene datos sin leer en su buffer de recepción hace que TCP
     * mande un RST, y un RST descarta lo que hubiera en camino <b>en las dos direcciones</b>. El
     * otro lado no recibe el GOAWAY que se le acaba de mandar: ve la conexión reiniciada y no
     * sabe si fue un error suyo, uno nuestro o un cable.
     *
     * <p>Así que: se vacía lo escrito, se cierra la mitad de escritura —que manda un FIN y le
     * dice al otro que no habrá más— y se lee lo que quede hasta el final. Es la diferencia
     * entre despedirse y colgar a media frase.
     */
    private void cerrarConSuavidad() {
        try {
            out.flush();
            socketCrudo.shutdownOutput();
        } catch (IOException yaEstaba) {
            return;
        }
        try {
            // Un segundo es de sobra para que llegue el FIN del otro lado, y suficientemente
            // poco para que un cliente que no cierre nunca no retenga el hilo.
            socketCrudo.setSoTimeout(1_000);
            byte[] resto = new byte[4096];
            long tope = 0;
            while (in.read(resto) > 0 && (tope += resto.length) < 1 << 20) {
                // Se lee y se tira: solo interesa llegar al final sin provocar un RST.
            }
        } catch (IOException seFue) {
            // Se acabó el tiempo o se cerró: en los dos casos ya no hay nada que esperar.
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

    // ─── un flujo ────────────────────────────────────────────────────────────────────────────

    /** Un flujo: su ventana de salida y el cuerpo que va llegando por DATA. */
    static final class Flujo {

        final int id;
        final Tuberia entrada = new Tuberia();
        final java.util.concurrent.atomic.AtomicInteger ventana;
        volatile boolean finEntrada;

        /** Lo que la petición declaró en `content-length`, o -1 si no declaró nada. */
        volatile long declarado = -1;

        /** Lo que ha llegado de verdad en tramas DATA. */
        final java.util.concurrent.atomic.AtomicLong recibido =
                new java.util.concurrent.atomic.AtomicLong();

        /**
         * La ventana arranca en la que el cliente pidió por SETTINGS, no en la del RFC.
         *
         * <p>Antes usaba la constante, así que un flujo creado después de negociar nacía con
         * 65 535 aunque el cliente hubiera pedido un mega. La respuesta se paraba justo en ese
         * byte y se quedaba esperando un WINDOW_UPDATE que el cliente no tenía por qué mandar:
         * desde su punto de vista aún le cabía muchísimo más.
         */
        Flujo(int id, int ventanaInicial) {
            this.id = id;
            this.ventana = new java.util.concurrent.atomic.AtomicInteger(ventanaInicial);
        }

        void cerrarEntrada() {
            entrada.cerrar();
        }
    }

    /**
     * El cuerpo de la petición, que llega en tramas mientras el manejador ya está corriendo.
     *
     * <p>Es un {@link InputStream} porque eso es lo que espera {@link IncomingRequest}, y así el
     * resto del framework —multipart, JSON, formularios— funciona sin enterarse de que debajo
     * hay tramas y no un socket.
     */
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

    /** Rompe la conexión entera. */
    static final class ErrorConexion extends RuntimeException {

        final int codigo;

        ErrorConexion(int codigo, String mensaje) {
            super(mensaje);
            this.codigo = codigo;
        }
    }

    /** Rompe un flujo y deja la conexión en pie. */
    static final class ErrorFlujo extends RuntimeException {

        ErrorFlujo(String mensaje) {
            super(mensaje);
        }
    }
}
