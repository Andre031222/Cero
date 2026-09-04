package cero.core;

import cero.http.Server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

final class TrazadoTests {

    private TrazadoTests() {
    }

    static void run() throws Exception {
        Check.group("trazado W3C");

        formato();
        cabeceraQueLlega();
        cabeceraQueNoVale();
        enElLog();
        deExtremoAExtremo();
    }

    private static void formato() {
        Trace t = Trace.nueva();

        Check.equal("la traza son 32 hex", t.traza().length(), 32);
        Check.equal("el tramo son 16 hex", t.tramo().length(), 16);
        Check.that("todo en minúscula: la especificación no admite mayúsculas",
                t.traceparent().equals(t.traceparent().toLowerCase(java.util.Locale.ROOT)));
        Check.equal("la cabecera es version-traza-tramo-banderas",
                t.traceparent(), "00-" + t.traza() + "-" + t.tramo() + "-01");

        // Dos trazas seguidas no pueden coincidir, o el trazado no distingue nada.
        Set<String> vistas = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            vistas.add(Trace.nueva().traza());
        }
        Check.equal("500 trazas seguidas son 500 identificadores distintos", vistas.size(), 500);
    }

    private static void cabeceraQueLlega() {
        Trace t = Trace.deCabecera("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        Check.equal("la traza del padre se hereda",
                t.traza(), "4bf92f3577b34da6a3ce929d0e0e4736");
        Check.that("pero el tramo es propio: quien nos llamó es el padre, no nosotros",
                !t.tramo().equals("00f067aa0ba902b7"));
        Check.that("la bandera de muestreo se respeta", t.muestreada());

        Trace sinMuestrear = Trace.deCabecera(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");
        Check.that("y también cuando dice que no", !sinMuestrear.muestreada());

        // Una versión futura mantiene los cuatro primeros campos en su sitio: se acepta.
        Trace futura = Trace.deCabecera(
                "cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-loquesea");
        Check.equal("una versión futura con campos de más se sigue entendiendo",
                futura.traza(), "4bf92f3577b34da6a3ce929d0e0e4736");
    }

    /**
     * Una cabecera rota no tumba la petición: se descarta y se empieza traza propia. Devolver
     * 400 por esto convertiría un proxy mal configurado en una caída.
     */
    private static void cabeceraQueNoVale() {
        String heredada = "4bf92f3577b34da6a3ce929d0e0e4736";
        List<String> malas = List.of(
                "",
                "no-es-una-cabecera",
                "00-" + heredada,
                "00-" + heredada + "-00f067aa0ba902b7",
                "00-XXf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",
                "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01",
                "00-" + heredada + "-" + "0".repeat(16) + "-01",
                "ff-" + heredada + "-00f067aa0ba902b7-01",
                "00-" + heredada.toUpperCase(java.util.Locale.ROOT) + "-00f067aa0ba902b7-01");

        for (String mala : malas) {
            Trace t = Trace.deCabecera(mala);
            Check.that("se descarta y se empieza de nuevo: «" + mala + "»",
                    t != null && t.traza().length() == 32 && !t.traza().equals(heredada));
        }
        Check.that("sin cabecera también empieza una", Trace.deCabecera(null).traza().length() == 32);
    }

    /** El motivo de todo esto: que el identificador salga en el log sin pasarlo a mano. */
    private static void enElLog() {
        List<String> lineas = new ArrayList<>();
        Log.Nivel antes = Log.nivel();
        Log.nivel(Log.Nivel.DEBUG);
        Log.destino(lineas::add);
        try {
            Log.of("prueba").info("sin traza todavía");
            Check.that("sin trazado, la línea sale como siempre",
                    lineas.get(0).contains("sin traza todavía") && lineas.get(0).contains("prueba"));

            Trace t = Trace.nueva();
            Trace.establecer(t);
            try {
                Log.of("prueba").info("con traza");
                Check.that("con trazado, la línea lleva el identificador",
                        lineas.get(1).contains(t.traza()));
                Check.that("y sigue llevando el mensaje", lineas.get(1).contains("con traza"));
            } finally {
                Trace.limpiar();
            }

            Log.of("prueba").info("y después");
            Check.that("al limpiar deja de salir", !lineas.get(2).contains(t.traza()));
        } finally {
            Log.destino(linea -> System.out.println(linea));
            Log.nivel(antes);
        }
    }

    /**
     * Dos servicios de verdad: el de fuera llama al de dentro con {@link Http}, y la traza tiene
     * que ser la misma en los dos. Es la única prueba que dice si esto sirve para algo.
     */
    private static void deExtremoAExtremo() throws Exception {
        ConcurrentLinkedQueue<String> vistas = new ConcurrentLinkedQueue<>();

        Server dentro = Cero.app().port(0).quiet().reporter(cero.http.ErrorReporter.silent())
                .use(Trace.middleware())
                .routes(r -> r.get("/dentro", ctx -> {
                    vistas.add("dentro:" + ctx.trace().traza());
                    return Result.text("bien");
                }))
                .start();

        Server fuera = Cero.app().port(0).quiet().reporter(cero.http.ErrorReporter.silent())
                .use(Trace.middleware())
                .routes(r -> r.get("/fuera", ctx -> {
                    vistas.add("fuera:" + ctx.trace().traza());
                    Http.to("http://127.0.0.1:" + dentro.port() + "/dentro").get();
                    return Result.text("bien");
                }))
                .start();

        try {
            String heredada = "4bf92f3577b34da6a3ce929d0e0e4736";
            Http.Respuesta r = Http.to("http://127.0.0.1:" + fuera.port() + "/fuera")
                    .header("traceparent", "00-" + heredada + "-00f067aa0ba902b7-01")
                    .get();

            Check.equal("la petición responde", r.estado(), 200);
            Check.equal("la respuesta devuelve el identificador para poder citarlo",
                    r.cabecera("Trace-Id"), heredada);

            List<String> ordenadas = new ArrayList<>(vistas);
            Check.equal("los dos servicios se vieron", ordenadas.size(), 2);
            Check.that("el de fuera hereda la traza del cliente",
                    ordenadas.contains("fuera:" + heredada));
            Check.that("y el de dentro la recibe de él, no una nueva",
                    ordenadas.contains("dentro:" + heredada));

            // Segunda petición por la misma conexión: la traza no puede arrastrarse.
            vistas.clear();
            Http.to("http://127.0.0.1:" + fuera.port() + "/fuera").get();
            String segunda = new ArrayList<>(vistas).get(0);
            Check.that("una petición sin cabecera estrena traza, no hereda la anterior",
                    !segunda.contains(heredada));
        } finally {
            fuera.stop();
            dentro.stop();
        }
    }
}
