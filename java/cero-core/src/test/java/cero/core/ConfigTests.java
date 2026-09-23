package cero.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConfigTests {

    private ConfigTests() {
    }

    record Datos(String url, String usuario, int pool) {
    }

    static void run() {
        Check.group("configuración");

        Config config = Config.empty()
                .set("server.port", "9000")
                .set("server.host", "127.0.0.1")
                .set("app.debug", "true")
                .set("app.reintentos", "3")
                .set("app.timeout", "5000")
                .set("db.url", "jdbc:postgresql://localhost/cero")
                .set("db.usuario", "andre")
                .set("db.pool", "10");

        Check.equal("lee una clave", config.get("server.host"), "127.0.0.1");
        Check.equal("clave ausente devuelve null", config.get("no.existe"), null);
        Check.equal("clave ausente usa el respaldo", config.get("no.existe", "x"), "x");
        Check.equal("entero", config.getInt("server.port", 0), 9000);
        Check.equal("entero ausente usa respaldo", config.getInt("no.existe", 42), 42);
        Check.equal("largo", config.getLong("app.timeout", 0), 5_000L);
        Check.equal("booleano", config.getBoolean("app.debug", false), true);
        Check.that("has() detecta la clave", config.has("app.debug") && !config.has("app.nada"));

        Check.equal("under() recorta el prefijo", config.under("db").get("url"),
                "jdbc:postgresql://localhost/cero");
        Check.equal("under() no incluye otras secciones", config.under("db").size(), 3);

        Datos datos = config.bind(Datos.class, "db");
        Check.equal("bind: url", datos.url(), "jdbc:postgresql://localhost/cero");
        Check.equal("bind: usuario", datos.usuario(), "andre");
        Check.equal("bind: pool convertido a int", datos.pool(), 10);

        System.setProperty("cero.prueba.valor", "desde-propiedad");
        try {
            Check.equal("las propiedades del sistema con prefijo cero. se cargan",
                    Config.load("no-existe.properties").get("prueba.valor"), "desde-propiedad");
        } finally {
            System.clearProperty("cero.prueba.valor");
        }

        // Regresión: el prefijo pasó de cuatro letras a seis y el recorte estaba escrito a
        // mano, así que CERO_SERVER_PORT se leía como "o.server.port". No lo cazó nadie porque
        // System.getenv() no se puede tocar en el proceso y este camino no tenía prueba.
        Check.equal("CERO_ se recorta por la longitud del prefijo, no a mano",
                Config.claveDeEntorno("CERO_SERVER_PORT"), "server.port");
        Check.equal("y un solo tramo también",
                Config.claveDeEntorno("CERO_PUERTO"), "puerto");
        Check.that("lo que no lleva el prefijo se ignora",
                Config.claveDeEntorno("PATH") == null && Config.claveDeEntorno("OTRO_PUERTO") == null);

        Check.equal("cargar un recurso inexistente no falla",
                Config.load("no-existe.properties").get("nada"), null);

        avisosDeEntorno();
    }

    /** Una variable con el nombre viejo o mal escrito se aplicaba sin efecto y sin decir nada. */
    private static void avisosDeEntorno() {
        Map<String, String> entorno = new LinkedHashMap<>();
        entorno.put("CERO_SERVER_PORT", "9000");
        entorno.put("CERO_SERVER_MAXCONNECTIONS", "512");
        entorno.put("CERO_SESION_SEGURA", "true");
        entorno.put("PATH", "/usr/bin");

        Config config = Config.empty();
        config.absorbEnvironment(entorno);
        config.getInt("server.port", 0);
        config.has("server.maxConnections");

        List<String> avisos = capturar(config::revisarEntorno);

        Config sinCargar = Config.empty();
        List<String> silencioso = capturar(() -> sinCargar.revisarEntorno(entorno));
        Check.equal("sin loadConfig() avisa de que el entorno entero se ignora", silencioso.size(), 1);
        Check.that("y nombra las variables ignoradas",
                silencioso.get(0).contains("CERO_SERVER_PORT") && silencioso.get(0).contains("loadConfig()"));

        Check.equal("solo avisa de las variables que nadie lee", avisos.size(), 2);
        Check.that("señala la clave camelCase que el entorno no puede alcanzar",
                avisos.get(0).contains("CERO_SERVER_MAXCONNECTIONS")
                        && avisos.get(0).contains("server.maxConnections"));
        Check.that("y la variable que no corresponde a ninguna clave",
                avisos.get(1).contains("CERO_SESION_SEGURA"));
        Check.that("los avisos son WARN", avisos.stream().allMatch(linea -> linea.contains("WARN")));
    }

    private static List<String> capturar(Runnable accion) {
        List<String> lineas = new ArrayList<>();
        Log.Nivel previo = Log.nivel();
        Log.nivel(Log.Nivel.WARN);
        Log.destino(lineas::add);
        try {
            accion.run();
        } finally {
            Log.destino(linea -> System.out.println(linea));
            Log.nivel(previo);
        }
        return lineas;
    }
}
