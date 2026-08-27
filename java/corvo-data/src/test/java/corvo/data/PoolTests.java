package corvo.data;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

final class PoolTests {

    private PoolTests() {
    }

    static void run() {
        Check.group("Pool");
        prestamos();

        Check.group("transacciones");
        transacciones();

        Check.group("orígenes de datos");
        origenes();

        Check.group("pool bajo concurrencia");
        concurrencia();
        validacionDiferida();
    }

    private static void prestamos() {
        FakeDb.reset();
        Pool pool = Pool.to(FakeDb.URL).maxSize(2).validate(false).build();

        Connection primera = pool.borrow();
        Check.equal("abre una conexión al primer préstamo", FakeDb.opened.get(), 1);
        Check.equal("cuenta la activa", pool.active(), 1);
        Check.equal("y el tamaño", pool.size(), 1);

        pool.release(primera);
        Check.equal("al devolverla queda disponible", pool.available(), 1);
        Check.equal("y deja de estar activa", pool.active(), 0);

        Connection reusada = pool.borrow();
        Check.equal("la reutiliza sin abrir otra", FakeDb.opened.get(), 1);
        Check.that("es la misma conexión", reusada == primera);

        Connection segunda = pool.borrow();
        Check.equal("abre una segunda al pedir otra", FakeDb.opened.get(), 2);

        Pool agotado = Pool.to(FakeDb.URL).maxSize(1).validate(false)
                .borrowTimeoutMillis(150).build();
        agotado.borrow();
        Check.raises("superar el máximo espera y falla", DataException.class, agotado::borrow);
        agotado.close();

        pool.release(reusada);
        pool.release(segunda);
        pool.close();
        Check.equal("al cerrar se cierran las conexiones", FakeDb.closed.get() >= 2, true);
        Check.raises("un pool cerrado no presta", DataException.class, pool::borrow);

        FakeDb.reset();
        Pool descarta = Pool.to(FakeDb.URL).maxSize(2).validate(false).build();
        Connection rota = descarta.borrow();
        try {
            rota.close();
        } catch (Exception ignored) {
        }
        descarta.release(rota);
        Check.equal("una conexión cerrada no vuelve al pool", descarta.available(), 0);
        descarta.borrow();
        Check.equal("y se abre una nueva en su lugar", FakeDb.opened.get(), 2);
        descarta.close();

        FakeDb.reset();
        FakeDb.failNextConnect = true;
        Pool falla = Pool.to(FakeDb.URL).validate(false).build();
        Check.raises("si el driver falla, se informa", DataException.class, falla::borrow);
        FakeDb.failNextConnect = false;
        Check.equal("y no queda contabilizada", falla.size(), 0);
        falla.close();
    }

    private static void transacciones() {
        DataSources.clear();
        FakeDb.reset();
        DataSources.registerDefault(Pool.to(FakeDb.URL).validate(false).build());

        Check.that("fuera de una transacción no hay ninguna activa", !Tx.active());

        Tx.run(() -> {
            Check.that("dentro sí hay una activa", Tx.active());
            Db.open().exec("UPDATE t SET a = 1");
        });
        Check.equal("al terminar bien se confirma", FakeDb.commits.get(), 1);
        Check.equal("y no se deshace", FakeDb.rollbacks.get(), 0);
        Check.that("al salir ya no hay transacción", !Tx.active());

        try {
            Tx.run(() -> {
                Db.open().exec("UPDATE t SET a = 2");
                throw new IllegalStateException("fallo de negocio");
            });
        } catch (IllegalStateException expected) {
            Check.that("la excepción se propaga", expected.getMessage().equals("fallo de negocio"));
        }
        Check.equal("y se deshace la transacción", FakeDb.rollbacks.get(), 1);
        Check.equal("sin confirmar", FakeDb.commits.get(), 1);

        int antes = FakeDb.opened.get();
        Tx.run(() -> {
            Db.open().exec("UPDATE t SET a = 3");
            Db.open().exec("UPDATE t SET a = 4");
        });
        Check.equal("las consultas de una transacción comparten conexión",
                FakeDb.opened.get(), antes);

        FakeDb.commits.set(0);
        Tx.run(() -> Tx.run(() -> Db.open().exec("UPDATE t SET a = 5")));
        Check.equal("una transacción anidada se une a la externa", FakeDb.commits.get(), 1);

        Check.equal("call devuelve el resultado", Tx.call(() -> 42), 42);
    }

    private static void origenes() {
        DataSources.clear();
        FakeDb.reset();

        Check.raises("pedir un origen no registrado falla", DataException.class,
                DataSources::primary);

        Pool principal = Pool.to(FakeDb.URL).validate(false).build();
        Pool informes = Pool.to(FakeDb.URL).validate(false).build();
        DataSources.registerDefault(principal);
        DataSources.register("informes", informes);

        Check.that("primary devuelve el registrado por defecto", DataSources.primary() == principal);
        Check.that("get devuelve el nombrado", DataSources.get("informes") == informes);
        Check.that("has distingue", DataSources.has("informes") && !DataSources.has("nada"));

        FakeDb.willReturn(List.of(FakeDb.row("id", 1L)));
        Db.open("informes").select("t");
        Check.equal("Db usa el origen indicado", FakeDb.lastQuery().sql(), "SELECT * FROM t");

        Check.raises("un nombre inexistente lo dice", DataException.class,
                () -> Db.open("fantasma").select("t"));

        List<String> nombres = new ArrayList<>();
        try {
            DataSources.get("fantasma");
        } catch (DataException error) {
            nombres.add(error.getMessage());
        }
        Check.that("el mensaje enumera los registrados",
                nombres.get(0).contains("informes"));

        DataSources.clear();
        Check.that("clear vacía el registro", !DataSources.has("informes"));
    }

    /**
     * Un pool que reparte más conexiones de las que dice su tope es una bomba: la base de datos
     * corta por su lado y la aplicación no sabe por qué. Se pide y se devuelve desde muchos hilos
     * a la vez y se comprueba que el techo aguanta.
     */
    private static void concurrencia() {
        FakeDb.reset();
        int tope = 4;
        Pool pool = Pool.to(FakeDb.URL).maxSize(tope).validate(false)
                .borrowTimeoutMillis(2_000).build();

        int hilos = 24;
        int vueltas = 40;
        java.util.concurrent.atomic.AtomicInteger enUso = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger pico = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger fallos = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch salida = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch llegada = new java.util.concurrent.CountDownLatch(hilos);

        for (int i = 0; i < hilos; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    salida.await();
                    for (int vuelta = 0; vuelta < vueltas; vuelta++) {
                        java.sql.Connection prestada = pool.borrow();
                        int ahora = enUso.incrementAndGet();
                        pico.updateAndGet(anterior -> Math.max(anterior, ahora));
                        Thread.yield();
                        enUso.decrementAndGet();
                        pool.release(prestada);
                    }
                } catch (RuntimeException | InterruptedException fallo) {
                    fallos.incrementAndGet();
                } finally {
                    llegada.countDown();
                }
            });
        }
        salida.countDown();
        try {
            Check.that("todos los hilos terminan",
                    llegada.await(30, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
        }

        Check.equal("nadie falló pidiendo o devolviendo", fallos.get(), 0);
        Check.that("nunca hubo más prestadas que el tope", pico.get() <= tope);
        Check.that("no se abrieron más conexiones que el tope", FakeDb.opened.get() <= tope);
        Check.equal("al final no queda ninguna activa", pool.active(), 0);
        Check.that("y las abiertas siguen disponibles", pool.available() == pool.size());
        pool.close();
    }

    /** Validar en cada préstamo es un viaje a la base de datos; con intervalo, se ahorra. */
    private static void validacionDiferida() {
        FakeDb.reset();
        Pool siempre = Pool.to(FakeDb.URL).maxSize(1).validate(true).build();
        for (int i = 0; i < 5; i++) {
            siempre.release(siempre.borrow());
        }
        int conValidacionSiempre = FakeDb.validated.get();
        Check.that("sin intervalo se valida en cada préstamo", conValidacionSiempre >= 5);
        siempre.close();

        FakeDb.reset();
        Pool diferido = Pool.to(FakeDb.URL).maxSize(1).validate(true)
                .validateEvery(java.time.Duration.ofMinutes(1)).build();
        for (int i = 0; i < 5; i++) {
            diferido.release(diferido.borrow());
        }
        Check.equal("con intervalo se valida una sola vez", FakeDb.validated.get(), 1);
        diferido.close();
    }
}