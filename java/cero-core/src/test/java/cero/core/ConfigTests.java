package cero.core;

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

        // Regresión: el prefijo pasó de LUX_ (4) a CERO_ (6) y el recorte estaba escrito a
        // mano, así que CERO_SERVER_PORT se leía como "o.server.port". No lo cazó nadie porque
        // System.getenv() no se puede tocar en el proceso y este camino no tenía prueba.
        Check.equal("CERO_ se recorta por la longitud del prefijo, no a mano",
                Config.claveDeEntorno("CERO_SERVER_PORT"), "server.port");
        Check.equal("y un solo tramo también",
                Config.claveDeEntorno("CERO_PUERTO"), "puerto");
        Check.that("lo que no lleva el prefijo se ignora",
                Config.claveDeEntorno("PATH") == null && Config.claveDeEntorno("LUX_PUERTO") == null);

        Check.equal("cargar un recurso inexistente no falla",
                Config.load("no-existe.properties").get("nada"), null);
    }
}
