package lux.core;

/**
 * El contexto de la petición que está atendiendo este hilo.
 *
 * <p>Existe para que {@link Controller} pueda ofrecer ayudas sin recibir el {@code Context} por
 * parámetro. Es un {@link ThreadLocal} y no un campo del controlador **a propósito**: los
 * controladores son una sola instancia compartida y hay un hilo virtual por conexión, así que un
 * campo lo pisarían dos peticiones simultáneas. Con hilos virtuales un ThreadLocal es barato —
 * cada petición tiene el suyo y muere con él.
 *
 * <p>Solo lo pone y lo quita el {@link Dispatcher}, en un {@code try/finally}. Fuera de una
 * petición no hay nada, y preguntar da error en vez de devolver el contexto de otro.
 */
public final class Current {

    private static final ThreadLocal<Context> ACTUAL = new ThreadLocal<>();

    private Current() {
    }

    /** El contexto de esta petición. */
    public static Context context() {
        Context contexto = ACTUAL.get();
        if (contexto == null) {
            throw new IllegalStateException(
                    "no hay petición en curso en este hilo — Current.context() solo vale dentro de "
                            + "un controlador o un middleware, no en un hilo que hayas lanzado tú");
        }
        return contexto;
    }

    /** {@code true} si este hilo está atendiendo una petición. */
    public static boolean present() {
        return ACTUAL.get() != null;
    }

    static void enter(Context contexto) {
        ACTUAL.set(contexto);
    }

    static void exit() {
        ACTUAL.remove();
    }
}
