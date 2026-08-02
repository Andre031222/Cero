package lux.http;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class Watchdog implements AutoCloseable {

    private final ScheduledExecutorService timer;

    Watchdog() {
        timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "lux-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    ScheduledFuture<?> arm(long millis, Runnable action) {
        return timer.schedule(action, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
