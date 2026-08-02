package lux.core;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class Log {

    public enum Nivel { DEBUG, INFO, WARN, ERROR, NADA }

    private static final DateTimeFormatter RELOJ =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final ConcurrentHashMap<String, Log> REGISTRO = new ConcurrentHashMap<>();

    private static volatile Nivel nivel = nivelInicial();
    private static volatile Consumer<String> destino = linea -> salida().println(linea);

    private final String nombre;

    private Log(String nombre) {
        this.nombre = nombre;
    }

    public static Log of(Class<?> tipo) {
        return of(tipo.getSimpleName());
    }

    public static Log of(String nombre) {
        return REGISTRO.computeIfAbsent(nombre, Log::new);
    }

    public static void nivel(Nivel value) {
        nivel = value;
    }

    public static Nivel nivel() {
        return nivel;
    }

    public static void destino(Consumer<String> value) {
        destino = value;
    }

    public void debug(String mensaje, Object... datos) {
        emitir(Nivel.DEBUG, mensaje, datos, null);
    }

    public void info(String mensaje, Object... datos) {
        emitir(Nivel.INFO, mensaje, datos, null);
    }

    public void warn(String mensaje, Object... datos) {
        emitir(Nivel.WARN, mensaje, datos, null);
    }

    public void error(String mensaje, Object... datos) {
        emitir(Nivel.ERROR, mensaje, datos, null);
    }

    public void error(String mensaje, Throwable fallo) {
        emitir(Nivel.ERROR, mensaje, new Object[0], fallo);
    }

    public boolean activo(Nivel candidato) {
        return candidato.ordinal() >= nivel.ordinal() && nivel != Nivel.NADA;
    }

    private void emitir(Nivel candidato, String mensaje, Object[] datos, Throwable fallo) {
        if (!activo(candidato)) {
            return;
        }
        StringBuilder linea = new StringBuilder(96)
                .append(RELOJ.format(Instant.now()))
                .append("  ").append(etiqueta(candidato))
                .append("  ").append(nombre)
                .append("  ").append(interpolar(mensaje, datos));

        if (fallo != null) {
            linea.append("  ").append(fallo.getClass().getSimpleName())
                    .append(": ").append(fallo.getMessage());
        }
        destino.accept(linea.toString());

        if (fallo != null && activo(Nivel.DEBUG)) {
            fallo.printStackTrace(System.err);
        }
    }

    static String interpolar(String plantilla, Object[] datos) {
        if (datos == null || datos.length == 0) {
            return plantilla;
        }
        StringBuilder salida = new StringBuilder(plantilla.length() + 16);
        int siguiente = 0;
        int desde = 0;
        int marca;
        while ((marca = plantilla.indexOf("{}", desde)) >= 0) {
            salida.append(plantilla, desde, marca);
            salida.append(siguiente < datos.length ? String.valueOf(datos[siguiente++]) : "{}");
            desde = marca + 2;
        }
        return salida.append(plantilla, desde, plantilla.length()).toString();
    }

    private static String etiqueta(Nivel candidato) {
        return switch (candidato) {
            case DEBUG -> "DEBUG";
            case INFO -> "INFO ";
            case WARN -> "WARN ";
            case ERROR -> "ERROR";
            case NADA -> "     ";
        };
    }

    private static PrintStream salida() {
        return System.out;
    }

    private static Nivel nivelInicial() {
        String declarado = System.getProperty("lux.log", System.getenv("LUX_LOG"));
        if (declarado == null) {
            return Nivel.INFO;
        }
        try {
            return Nivel.valueOf(declarado.trim().toUpperCase());
        } catch (IllegalArgumentException desconocido) {
            return Nivel.INFO;
        }
    }
}
