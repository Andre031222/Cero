package lux.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class AccessLog implements Middleware {

    private static final DateTimeFormatter RELOJ =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z").withZone(ZoneId.systemDefault());

    private final boolean combinado;
    private final List<String> ignoradas = new ArrayList<>();

    private Consumer<String> destino = System.out::println;
    private boolean detrasDeProxy;

    private AccessLog(boolean combinado) {
        this.combinado = combinado;
    }

    public static AccessLog compact() {
        return new AccessLog(false);
    }

    public static AccessLog combined() {
        return new AccessLog(true);
    }

    public AccessLog to(Consumer<String> value) {
        destino = value;
        return this;
    }

    public AccessLog ignore(String... prefijos) {
        ignoradas.addAll(List.of(prefijos));
        return this;
    }

    public AccessLog behindProxy(boolean value) {
        detrasDeProxy = value;
        return this;
    }

    @Override
    public Object handle(Context context, Chain chain) throws Exception {
        if (ignorada(context.path())) {
            return chain.proceed(context);
        }
        long inicio = System.nanoTime();
        int estado = 200;
        try {
            Object salida = chain.proceed(context);
            estado = context.response().status();
            return salida;
        } catch (lux.http.HttpException fallo) {
            estado = fallo.status();
            throw fallo;
        } catch (Exception fallo) {
            estado = 500;
            throw fallo;
        } finally {
            destino.accept(linea(context, estado, (System.nanoTime() - inicio) / 1_000_000));
        }
    }

    private String linea(Context context, int estado, long millis) {
        String destinoPeticion = context.path()
                + (context.request().rawQuery() == null ? "" : "?" + context.request().rawQuery());

        if (!combinado) {
            return String.format("%s  %-6s %-3d %5d ms  %s",
                    RELOJ.format(Instant.now()), context.method(), estado, millis, destinoPeticion);
        }

        return String.format("%s - %s [%s] \"%s %s %s\" %d %d %d \"%s\" \"%s\"",
                cliente(context),
                context.principal() == null ? "-" : context.principal().id(),
                RELOJ.format(Instant.now()),
                context.method(), destinoPeticion, context.request().protocol(),
                estado, millis,
                longitud(context),
                valor(context.header("Referer")),
                valor(context.header("User-Agent")));
    }

    private String cliente(Context context) {
        if (detrasDeProxy) {
            String reenviado = context.header("X-Forwarded-For");
            if (reenviado != null && !reenviado.isBlank()) {
                int coma = reenviado.indexOf(',');
                return (coma < 0 ? reenviado : reenviado.substring(0, coma)).trim();
            }
        }
        String remoto = context.request().remoteAddress();
        return remoto == null || remoto.isEmpty() ? "-" : remoto;
    }

    private static long longitud(Context context) {
        String declarada = context.response().headers().get("Content-Length");
        if (declarada == null) {
            return 0;
        }
        try {
            return Long.parseLong(declarada);
        } catch (NumberFormatException sinDeclarar) {
            return 0;
        }
    }

    private static String valor(String cabecera) {
        return cabecera == null || cabecera.isBlank() ? "-" : cabecera.replace("\"", "'");
    }

    private boolean ignorada(String ruta) {
        for (String prefijo : ignoradas) {
            if (ruta.startsWith(prefijo)) {
                return true;
            }
        }
        return false;
    }
}
