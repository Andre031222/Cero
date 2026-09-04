package cero.core;

import java.security.SecureRandom;

/**
 * El identificador que sigue a una petición por todos los servicios que toca.
 *
 * <p>Formato <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>, que es el que
 * entienden Jaeger, Tempo, Zipkin, Datadog y los agentes de OpenTelemetry sin traducción por
 * medio. Una cabecera:
 *
 * <pre>
 *   traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 *                ‾‾  ‾‾ traza (32 hex) ‾‾            ‾ tramo (16) ‾   ‾‾ banderas
 * </pre>
 *
 * <p>Se activa como middleware:
 *
 * <pre>
 *   Cero.app().use(Trace.middleware()).start();
 * </pre>
 *
 * <p>A partir de ahí, <b>el identificador aparece solo</b>: en cada línea de {@link Log}, en el
 * log de acceso y en las llamadas salientes de {@link Http}. Ese es todo el motivo de que esto
 * exista — un trazado que hay que pasar a mano de método en método se olvida en el primer
 * método que no lo pasa, y entonces no sirve para nada.
 *
 * <p>No trae exportador de tramos ni mide duraciones por operación: para eso está un agente de
 * OpenTelemetry, que no es asunto del framework. Lo que Cero pone es lo que solo Cero puede
 * poner — que el identificador entre, viaje y salga.
 */
public final class Trace {

    /** Sin traza en curso vale {@code null}. Se limpia siempre: la conexión reusa el hilo. */
    private static final ThreadLocal<Trace> ACTUAL = new ThreadLocal<>();

    private static final SecureRandom AZAR = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Una traza sin recorrido: 32 ceros. Un padre así se descarta y se empieza de nuevo. */
    private static final String TRAZA_NULA = "0".repeat(32);
    private static final String TRAMO_NULO = "0".repeat(16);

    private final String traza;
    private final String tramo;
    private final boolean muestreada;

    private Trace(String traza, String tramo, boolean muestreada) {
        this.traza = traza;
        this.tramo = tramo;
        this.muestreada = muestreada;
    }

    /** Una traza nueva, con su primer tramo. */
    public static Trace nueva() {
        return new Trace(hex(16), hex(8), true);
    }

    /**
     * Continúa la traza que trae la cabecera, o empieza una si no vale.
     *
     * <p>Una cabecera inválida no es un error que deba tumbar la petición: se descarta en
     * silencio y se empieza una traza propia. Es lo que manda la especificación, y además es lo
     * sensato — un proxy mal configurado no debería devolver 400 a nadie.
     *
     * <p>El tramo siempre es nuevo aunque la traza se herede: quien nos llamó es nuestro padre,
     * no nosotros. Reutilizar su identificador de tramo mezclaría dos servicios en uno.
     */
    public static Trace deCabecera(String traceparent) {
        if (traceparent == null) {
            return nueva();
        }
        String[] partes = traceparent.trim().split("-");
        if (partes.length < 4) {
            return nueva();
        }
        String version = partes[0];
        // Sin normalizar a minúscula: la especificación solo admite hex en minúscula, así que
        // pasarlo por toLowerCase aceptaría una cabecera inválida en vez de descartarla — y
        // dos servicios acabarían con el mismo identificador escrito de dos maneras.
        String traza = partes[1];
        String banderas = partes[3];

        // La versión ff está reservada y no vale. Las demás sí, incluidas las futuras: el
        // formato garantiza que los cuatro primeros campos no cambian de sitio.
        boolean valida = esHex(version, 2) && !version.equals("ff")
                && esHex(traza, 32) && !traza.equals(TRAZA_NULA)
                && esHex(partes[2], 16) && !partes[2].equals(TRAMO_NULO)
                && esHex(banderas, 2);
        if (!valida) {
            return nueva();
        }
        boolean muestreada = (Integer.parseInt(banderas, 16) & 0x01) != 0;
        return new Trace(traza, hex(8), muestreada);
    }

    /** La traza de la petición que se está atendiendo en este hilo, o {@code null}. */
    public static Trace actual() {
        return ACTUAL.get();
    }

    /** El identificador de la traza actual, o {@code null}. Para meterlo en una línea de log. */
    public static String idActual() {
        Trace ahora = ACTUAL.get();
        return ahora == null ? null : ahora.traza();
    }

    static void establecer(Trace valor) {
        ACTUAL.set(valor);
    }

    static void limpiar() {
        ACTUAL.remove();
    }

    /** 32 caracteres hex. Es lo que se busca en el panel cuando alguien reporta un fallo. */
    public String traza() {
        return traza;
    }

    /** 16 caracteres hex: este servicio dentro de la traza. */
    public String tramo() {
        return tramo;
    }

    /** Si quien nos llamó pidió que esta traza se guarde. */
    public boolean muestreada() {
        return muestreada;
    }

    /** La cabecera para la siguiente llamada. Lo que este servicio pasa río abajo. */
    public String traceparent() {
        return "00-" + traza + "-" + tramo + "-" + (muestreada ? "01" : "00");
    }

    @Override
    public String toString() {
        return traceparent();
    }

    /**
     * El middleware. Lee la cabecera, deja la traza a mano de todo el hilo y la quita al salir.
     *
     * <p>Quitarla no es opcional: con keep-alive, la misma conexión —y el mismo hilo virtual—
     * atiende varias peticiones seguidas. Sin limpiar, la segunda heredaría la traza de la
     * primera y dos peticiones distintas contarían como una.
     */
    public static Middleware middleware() {
        return new Trazado(true);
    }

    /**
     * Igual, pero sin devolver el identificador al cliente.
     *
     * <p>Por defecto la respuesta lleva {@code Trace-Id}, que es lo que convierte «me dio error»
     * en «me dio error, aquí tienes el número». Si el servicio da a internet y no quieres
     * enseñar nada de dentro, esta variante lo calla.
     */
    public static Middleware middlewareCallado() {
        return new Trazado(false);
    }

    private record Trazado(boolean devuelveCabecera) implements Middleware {

        @Override
        public Object handle(Context context, Chain chain) throws Exception {
            Trace traza = deCabecera(context.header("traceparent"));
            establecer(traza);
            try {
                if (devuelveCabecera) {
                    context.response().header("Trace-Id", traza.traza());
                }
                return chain.proceed(context);
            } finally {
                limpiar();
            }
        }
    }

    private static String hex(int bytes) {
        byte[] crudo = new byte[bytes];
        AZAR.nextBytes(crudo);
        char[] salida = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            salida[i * 2] = HEX[(crudo[i] >> 4) & 0xF];
            salida[i * 2 + 1] = HEX[crudo[i] & 0xF];
        }
        return new String(salida);
    }

    private static boolean esHex(String texto, int largo) {
        if (texto.length() != largo) {
            return false;
        }
        for (int i = 0; i < largo; i++) {
            char c = texto.charAt(i);
            boolean digito = c >= '0' && c <= '9';
            boolean minuscula = c >= 'a' && c <= 'f';
            if (!digito && !minuscula) {
                return false;
            }
        }
        return true;
    }
}
