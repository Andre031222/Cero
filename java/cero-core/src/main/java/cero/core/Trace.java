package cero.core;

import java.security.SecureRandom;

public final class Trace {

    private static final ThreadLocal<Trace> ACTUAL = new ThreadLocal<>();

    private static final SecureRandom AZAR = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

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

    public static Trace nueva() {
        return new Trace(hex(16), hex(8), true);
    }

    public static Trace deCabecera(String traceparent) {
        if (traceparent == null) {
            return nueva();
        }
        String[] partes = traceparent.trim().split("-");
        if (partes.length < 4) {
            return nueva();
        }
        String version = partes[0];
        String traza = partes[1];
        String banderas = partes[3];

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

    public static Trace actual() {
        return ACTUAL.get();
    }

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

    public String traza() {
        return traza;
    }

    public String tramo() {
        return tramo;
    }

    public boolean muestreada() {
        return muestreada;
    }

    public String traceparent() {
        return "00-" + traza + "-" + tramo + "-" + (muestreada ? "01" : "00");
    }

    @Override
    public String toString() {
        return traceparent();
    }

    public static Middleware middleware() {
        return new Trazado(true);
    }

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
