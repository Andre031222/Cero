package corvo.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Dos endpoints que un supervisor puede consultar: si el proceso vive y si puede atender.
 *
 * <p>No son el mismo. Un proceso puede estar vivo y no poder atender —la base de datos no
 * responde, un servicio del que depende está caído— y confundirlos hace daño en las dos
 * direcciones: si el supervisor reinicia por lo que solo era una base de datos lenta, cambia un
 * problema pasajero por una caída; y si nunca reinicia, un proceso colgado se queda colgado.
 *
 * <pre>
 *   Corvo.app()
 *        .health(Health.checks()
 *            .ready("bd", () -&gt; db.ping())
 *            .ready("correo", () -&gt; mail.reachable()))
 *        .start();
 * </pre>
 *
 * <p>Quedan en {@code /corvo/vivo} y {@code /corvo/listo}. El primero contesta 200 siempre que el
 * proceso pueda responder, y por eso no admite comprobaciones: si aceptara una, dejaría de medir
 * lo que dice medir.
 */
public final class Health {

    /** Una comprobación con nombre. Devuelve false o lanza si no está lista. */
    private record Comprobacion(String nombre, Callable<Boolean> prueba) {
    }

    private final List<Comprobacion> comprobaciones = new ArrayList<>();
    private final long arranque = System.currentTimeMillis();

    private Health() {
    }

    public static Health checks() {
        return new Health();
    }

    /**
     * Añade una comprobación a {@code /corvo/listo}.
     *
     * <p>Debe ser barata y tener su propio límite de tiempo. Un supervisor consulta esto cada
     * pocos segundos: una comprobación que tarde más que ese intervalo se apila consigo misma.
     */
    public Health ready(String nombre, Callable<Boolean> prueba) {
        comprobaciones.add(new Comprobacion(nombre, prueba));
        return this;
    }

    /** El proceso responde. Nada más — y eso es exactamente lo que tiene que significar. */
    public Endpoint liveEndpoint() {
        return context -> Result.json(Map.of(
                "estado", "vivo",
                "activoMs", System.currentTimeMillis() - arranque));
    }

    /**
     * El proceso puede atender: todas las comprobaciones pasan.
     *
     * <p>Responde 503 si alguna falla, porque un 200 con un cuerpo que dice «mal» no lo lee
     * ningún supervisor: miran el código.
     *
     * <p>Una comprobación que lanza cuenta como fallo, y su mensaje va en la respuesta. Tragarse
     * la excepción dejaría un «no listo» sin explicación justo cuando hace falta.
     */
    public Endpoint readyEndpoint() {
        return context -> {
            Map<String, Object> detalle = new LinkedHashMap<>();
            boolean listo = true;
            for (Comprobacion c : comprobaciones) {
                try {
                    boolean bien = Boolean.TRUE.equals(c.prueba().call());
                    detalle.put(c.nombre(), bien ? "bien" : "mal");
                    listo &= bien;
                } catch (Exception fallo) {
                    detalle.put(c.nombre(), "mal: " + mensaje(fallo));
                    listo = false;
                }
            }
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("estado", listo ? "listo" : "no listo");
            cuerpo.put("comprobaciones", detalle);
            return Result.json(cuerpo).status(listo ? 200 : 503);
        };
    }

    private static String mensaje(Exception fallo) {
        String texto = fallo.getMessage();
        return texto == null || texto.isBlank() ? fallo.getClass().getSimpleName() : texto;
    }
}
