package corvo.core;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

/**
 * Expresión cron de cinco campos: minuto hora díaDelMes mes díaDeSemana.
 * Admite {@code *}, listas {@code 1,2}, rangos {@code 1-5} y pasos {@code  /15}.
 * El domingo es 0 y también 7.
 */
public final class Cron {

    private final BitSet minutos = new BitSet(60);
    private final BitSet horas = new BitSet(24);
    private final BitSet dias = new BitSet(32);
    private final BitSet meses = new BitSet(13);
    private final BitSet semana = new BitSet(8);
    private final String origen;

    private Cron(String origen) {
        this.origen = origen;
    }

    public static Cron of(String expresion) {
        String[] campos = expresion.trim().split("\\s+");
        if (campos.length != 5) {
            throw new IllegalArgumentException(
                    "cron necesita 5 campos (minuto hora día mes díaSemana), llegaron "
                            + campos.length + ": " + expresion);
        }
        Cron cron = new Cron(expresion.trim());
        llenar(cron.minutos, campos[0], 0, 59, expresion);
        llenar(cron.horas, campos[1], 0, 23, expresion);
        llenar(cron.dias, campos[2], 1, 31, expresion);
        llenar(cron.meses, campos[3], 1, 12, expresion);
        llenar(cron.semana, campos[4], 0, 7, expresion);
        if (cron.semana.get(7)) {
            cron.semana.set(0);
        }
        return cron;
    }

    public boolean coincide(LocalDateTime momento) {
        return minutos.get(momento.getMinute())
                && horas.get(momento.getHour())
                && dias.get(momento.getDayOfMonth())
                && meses.get(momento.getMonthValue())
                && semana.get(momento.getDayOfWeek().getValue() % 7);
    }

    /** Siguiente instante que cumple la expresión, buscando hasta cuatro años por delante. */
    public LocalDateTime siguiente(LocalDateTime desde) {
        LocalDateTime candidato = desde.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        long limite = 4L * 366 * 24 * 60;
        for (long i = 0; i < limite; i++) {
            if (coincide(candidato)) {
                return candidato;
            }
            candidato = candidato.plusMinutes(1);
        }
        throw new IllegalStateException("la expresión no se cumple nunca: " + origen);
    }

    public String origen() {
        return origen;
    }

    @Override
    public String toString() {
        return origen;
    }

    private static void llenar(BitSet destino, String campo, int min, int max, String completa) {
        for (String parte : campo.split(",")) {
            int paso = 1;
            String rango = parte;

            int barra = parte.indexOf('/');
            if (barra >= 0) {
                rango = parte.substring(0, barra);
                paso = entero(parte.substring(barra + 1), completa);
                if (paso < 1) {
                    throw new IllegalArgumentException("paso inválido en «" + parte + "»: " + completa);
                }
            }

            int desde;
            int hasta;
            if (rango.equals("*")) {
                desde = min;
                hasta = max;
            } else if (rango.contains("-")) {
                String[] extremos = rango.split("-", 2);
                desde = entero(extremos[0], completa);
                hasta = entero(extremos[1], completa);
            } else {
                desde = entero(rango, completa);
                hasta = desde;
            }

            if (desde < min || hasta > max || desde > hasta) {
                throw new IllegalArgumentException(
                        "valor fuera de rango en «" + parte + "» (se admite " + min + "-" + max
                                + "): " + completa);
            }
            for (int v = desde; v <= hasta; v += paso) {
                destino.set(v);
            }
        }
    }

    private static int entero(String texto, String completa) {
        try {
            return Integer.parseInt(texto.trim());
        } catch (NumberFormatException noEsNumero) {
            throw new IllegalArgumentException("«" + texto + "» no es un número: " + completa);
        }
    }
}
