package cero.core;

public final class Current {

    private static final ThreadLocal<Context> ACTUAL = new ThreadLocal<>();

    private Current() {
    }

    public static Context context() {
        Context contexto = ACTUAL.get();
        if (contexto == null) {
            throw new IllegalStateException(
                    "no hay petición en curso en este hilo — Current.context() solo vale dentro de "
                            + "un controlador o un middleware, no en un hilo que hayas lanzado tú");
        }
        return contexto;
    }

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
