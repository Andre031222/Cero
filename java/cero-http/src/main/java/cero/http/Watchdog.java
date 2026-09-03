package cero.http;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Corta las peticiones que se pasan del tiempo permitido: un hilo barre las conexiones abiertas
 * y aborta las que llevan demasiado dentro del handler. Por petición cuesta dos escrituras de un
 * {@code volatile}, sin programar ni cancelar nada.
 */
final class Watchdog implements AutoCloseable {

    interface Vigilada {

        /** Instante límite en nanos de {@link System#nanoTime}, o 0 si no hay nada en vuelo. */
        long limiteNanos();

        void abortar();
    }

    private final Set<Vigilada> abiertas = ConcurrentHashMap.newKeySet();
    private final Thread barrendero;
    private volatile boolean corriendo = true;

    /** Con un plazo de 0 o menos no arranca ningún hilo. */
    Watchdog(int timeoutMillis) {
        if (timeoutMillis <= 0) {
            barrendero = null;
            return;
        }
        long tick = Math.max(25, Math.min(500, timeoutMillis / 4L));
        barrendero = new Thread(() -> barrer(tick), "cero-watchdog");
        barrendero.setDaemon(true);
        barrendero.start();
    }

    void vigilar(Vigilada conexion) {
        if (barrendero != null) {
            abiertas.add(conexion);
        }
    }

    void soltar(Vigilada conexion) {
        if (barrendero != null) {
            abiertas.remove(conexion);
        }
    }

    private void barrer(long tick) {
        while (corriendo) {
            try {
                Thread.sleep(tick);
            } catch (InterruptedException interrumpido) {
                Thread.currentThread().interrupt();
                return;
            }
            long ahora = System.nanoTime();
            for (Vigilada conexion : abiertas) {
                long limite = conexion.limiteNanos();
                // La resta con signo aguanta el desbordamiento de nanoTime; comparar directamente no.
                if (limite != 0 && ahora - limite >= 0) {
                    conexion.abortar();
                }
            }
        }
    }

    @Override
    public void close() {
        corriendo = false;
        if (barrendero != null) {
            barrendero.interrupt();
        }
        abiertas.clear();
    }
}
