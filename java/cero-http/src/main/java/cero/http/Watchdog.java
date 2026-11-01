package cero.http;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class Watchdog implements AutoCloseable {

    interface Vigilada {

        long limiteNanos();

        void abortar();
    }

    private final Set<Vigilada> abiertas = ConcurrentHashMap.newKeySet();
    private final Thread barrendero;
    private volatile boolean corriendo = true;

    Watchdog(int timeoutMillis) {
        long tick = Math.max(25, Math.min(500, timeoutMillis / 4L));
        barrendero = new Thread(() -> barrer(tick), "cero-watchdog");
        barrendero.setDaemon(true);
        barrendero.start();
    }

    void vigilar(Vigilada conexion) {
        abiertas.add(conexion);
    }

    void soltar(Vigilada conexion) {
        abiertas.remove(conexion);
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
                if (limite != 0 && ahora - limite >= 0) {
                    conexion.abortar();
                }
            }
        }
    }

    @Override
    public void close() {
        corriendo = false;
        barrendero.interrupt();
        abiertas.clear();
    }
}
