/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  v3.1.1   : R. Andre Vilca Solorzano

package jxmvc.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Modo desarrollo — recarga automática de clases cuando cambia un .class.
 *
 * <p>Se activa automáticamente cuando el perfil activo es {@code dev}:
 * <pre>
 *   # application.properties
 *   jxmvc.profile = dev
 * </pre>
 *
 * <p>En modo dev el servidor detecta cambios en {@code /WEB-INF/classes}
 * e imprime aviso por log. La recarga real requiere soporte del contenedor
 * (Tomcat hot deploy) o reinicio manual con {@code mvn cargo:run}.
 *
 * <p>En producción ({@code jxmvc.profile = prod}) este módulo no hace nada.
 */
public final class JxDevMode {

    private static final JxLogger log = JxLogger.getLogger(JxDevMode.class);

    private static volatile boolean active = false;
    private static ScheduledExecutorService watcher;

    private JxDevMode() {}

    /**
     * Llamado desde {@link MainLxServlet#init()} — activa el modo dev si el perfil lo indica.
     */
    public static void init() {
        String profile = JxProfile.active();
        if (!"dev".equalsIgnoreCase(profile) && !"development".equalsIgnoreCase(profile)) return;

        active = true;
        log.info("[DevMode] Activo — perfil: {}", profile);

        String watchDir = BaseDbResolver.property("jxmvc.devmode.watchdir", "");
        if (watchDir.isBlank()) {
            log.info("[DevMode] Watcher desactivado (jxmvc.devmode.watchdir no configurado)");
            return;
        }

        Path dir = Paths.get(watchDir);
        if (!Files.isDirectory(dir)) {
            log.warn("[DevMode] Directorio no existe: {}", dir.toAbsolutePath());
            return;
        }

        startWatcher(dir);
    }

    public static boolean isActive() { return active; }

    public static void shutdown() {
        if (watcher != null) watcher.shutdownNow();
    }

    // ── Watcher interno ───────────────────────────────────────────────────

    private static void startWatcher(Path dir) {
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jx-devmode-watcher");
            t.setDaemon(true);
            return t;
        });

        final long[] lastSnapshot = {dirSnapshot(dir)};

        int intervalMs = Integer.parseInt(
            BaseDbResolver.property("jxmvc.devmode.interval", "1500"));

        watcher.scheduleWithFixedDelay(() -> {
            long current = dirSnapshot(dir);
            if (current != lastSnapshot[0]) {
                lastSnapshot[0] = current;
                log.info("[DevMode] Cambio detectado en {} — reinicia el servidor para recargar", dir);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        log.info("[DevMode] Vigilando {} cada {}ms", dir.toAbsolutePath(), intervalMs);
    }

    private static long dirSnapshot(Path dir) {
        final long[] hash = {0L};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    hash[0] ^= file.toString().hashCode() * 31L + attrs.lastModifiedTime().toMillis();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
        return hash[0];
    }
}
