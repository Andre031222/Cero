package lux.data;

import lux.http.SessionStore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** El almacén de sesiones sobre una tabla, contra H2 de verdad. */
final class JdbcSessionsTests {

    private JdbcSessionsTests() {
    }

    static void run() {
        Check.group("sesiones en tabla");

        DataSources.clear();
        DataSources.registerDefault(
                Pool.to("jdbc:h2:mem:sesiones;DB_CLOSE_DELAY=-1").validate(false).build());

        JdbcSessions almacen = JdbcSessions.of("lux_sesiones").createTable();
        try {
            ida(almacen);
            sinPisarse(almacen);
            barrido(almacen);
            concurrencia(almacen);
        } finally {
            DataSources.clear();
        }
    }

    private static void ida(JdbcSessions almacen) {
        long ahora = System.currentTimeMillis();
        Map<String, Object> valores = new HashMap<>();
        valores.put("usuario", "andre");
        valores.put("intentos", 3);

        almacen.save("s1", new SessionStore.Datos(valores, ahora, ahora));

        SessionStore.Datos leido = almacen.load("s1");
        Check.that("la sesión guardada se recupera", leido != null);
        Check.equal("con su texto", leido.valores().get("usuario"), "andre");
        Check.equal("y sus números", String.valueOf(leido.valores().get("intentos")), "3");
        Check.equal("guarda cuándo se creó", leido.creada(), ahora);

        Check.equal("una sesión que no existe da null", almacen.load("no-existe"), null);

        almacen.remove("s1");
        Check.equal("y borrada deja de estar", almacen.load("s1"), null);
    }

    private static void sinPisarse(JdbcSessions almacen) {
        long ahora = System.currentTimeMillis();
        almacen.save("s2", new SessionStore.Datos(new HashMap<>(), ahora, ahora));

        // Dos cambios sucesivos, cada uno sobre lo último guardado.
        almacen.update("s2", previo -> conClave(previo, "csrf", "token"));
        almacen.update("s2", previo -> conClave(previo, "carrito", "3 artículos"));

        SessionStore.Datos leido = almacen.load("s2");
        Check.equal("el primer cambio sigue ahí", leido.valores().get("csrf"), "token");
        Check.equal("y el segundo también", leido.valores().get("carrito"), "3 artículos");
    }

    private static void barrido(JdbcSessions almacen) {
        long viejo = System.currentTimeMillis() - 120_000;
        almacen.save("caducada", new SessionStore.Datos(new HashMap<>(), viejo, viejo));
        almacen.save("viva", new SessionStore.Datos(new HashMap<>(),
                System.currentTimeMillis(), System.currentTimeMillis()));

        almacen.sweep(60_000);

        Check.equal("el barrido se lleva la caducada", almacen.load("caducada"), null);
        Check.that("y respeta la viva", almacen.load("viva") != null);
    }

    /** Lo que justifica el UPDATE condicional: veinte escrituras a la vez, ninguna perdida. */
    private static void concurrencia(JdbcSessions almacen) {
        long ahora = System.currentTimeMillis();
        almacen.save("s3", new SessionStore.Datos(new HashMap<>(), ahora, ahora));

        int escritores = 20;
        CountDownLatch salida = new CountDownLatch(1);
        CountDownLatch fin = new CountDownLatch(escritores);

        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < escritores; i++) {
                int mio = i;
                hilos.execute(() -> {
                    try {
                        salida.await();
                        almacen.update("s3", previo -> conClave(previo, "k" + mio, "v" + mio));
                    } catch (Exception ignorado) {
                        // lo cuenta la comprobación de abajo
                    } finally {
                        fin.countDown();
                    }
                });
            }
            salida.countDown();
            Check.that("las 20 escrituras simultáneas terminan",
                    esperar(fin));
        }

        SessionStore.Datos leido = almacen.load("s3");
        int presentes = 0;
        for (int i = 0; i < escritores; i++) {
            if (("v" + i).equals(leido.valores().get("k" + i))) {
                presentes++;
            }
        }
        Check.equal("y las 20 claves están, ninguna se perdió", presentes, escritores);
    }

    private static boolean esperar(CountDownLatch fin) {
        try {
            return fin.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException cortado) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static SessionStore.Datos conClave(SessionStore.Datos previo, String clave, Object valor) {
        Map<String, Object> fusion = previo == null
                ? new HashMap<>() : new HashMap<>(previo.valores());
        fusion.put(clave, valor);
        long creada = previo == null ? System.currentTimeMillis() : previo.creada();
        return new SessionStore.Datos(fusion, creada, System.currentTimeMillis());
    }
}
