package cero.core;

import cero.http.ErrorReporter;
import cero.http.Server;

import java.net.http.HttpResponse;

final class MensajesTests {

    private MensajesTests() {
    }

    @Route("/saludos")
    static final class SaludosController {

        @Get
        public Object saludar(Context context) {
            return Result.text(context.t("saludo", "Richar"));
        }

        @Get("/idioma")
        public Object idioma(Context context) {
            return Result.text(context.idioma());
        }

        @Get("/incompleto")
        public Object incompleto(Context context) {
            return Result.text(context.t("faltaEnIngles"));
        }

        @Get("/inventado")
        public Object inventado(Context context) {
            return Result.text(context.t("no.existe.esta.clave"));
        }

        @Get("/apostrofe")
        public Object apostrofe(Context context) {
            return Result.text(context.t("apostrofe"));
        }
    }

    /**
     * Un motor de vistas mínimo. No usa cero-view a propósito: aquí lo que se prueba es que el
     * despachador entregue los textos como globales a QUIEN SEA que rinda, y cero-core no
     * depende de cero-view — es al revés.
     */
    private static ViewRenderer vistaDePrueba() {
        return new ViewRenderer() {
            @Override
            public String render(String plantilla, Object modelo) {
                return render(plantilla, modelo, java.util.Map.of());
            }

            @Override
            public String render(String plantilla, Object modelo, java.util.Map<String, Object> globales) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, String> textos =
                        (java.util.Map<String, String>) globales.get("t");
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> m = (java.util.Map<String, Object>) modelo;
                return (textos == null ? "sin textos" : textos.get("saludo")) + "|" + m.get("nombre");
            }
        };
    }

    @Route("/vista")
    static final class VistaController {
        @Get
        public Object pagina() {
            return Result.view("saludo.html", java.util.Map.of("nombre", "Richar"));
        }
    }

    static void run() throws Exception {
        Check.group("textos");
        sinRed();
        sinLlamarABase();
        baseEnOtroIdioma();

        Check.group("textos por HTTP");
        porHttp();

        Check.group("textos en plantillas");
        enPlantillas();
    }

    /**
     * El mapa `t` tiene que llegar a la vista sin que el controlador lo meta en su modelo: ese
     * es todo el motivo de que exista como global. Olvidarlo en un solo controlador dejaría esa
     * página sin traducir, y nadie lo vería hasta que un usuario se quejara.
     */
    private static void enPlantillas() throws Exception {
        Server servidor = Cero.app().port(0).quiet().reporter(ErrorReporter.silent())
                .messages(Messages.from("textos", "en", "qu").base("es"))
                .views(vistaDePrueba())
                .controllers(VistaController.class)
                .start();
        String base = "http://127.0.0.1:" + servidor.port();
        try {
            String es = Cliente.get(base + "/vista").body();
            Check.equal("la vista recibe los textos sin que el controlador los pase",
                    es, "Hola, {0}|Richar");

            String en = Cliente.get(base + "/vista", "Accept-Language", "en").body();
            Check.equal("y en el idioma de la petición", en, "Hello, {0}|Richar");
        } finally {
            servidor.stop();
        }
    }

    /**
     * El camino que nadie cubría: `from(...)` a secas, sin encadenar `base(...)`. El archivo sin
     * sufijo se guardaba bajo una clave vacía mientras el idioma base ya valía «es», así que la
     * negociación elegía «es» y la búsqueda no encontraba nada — la página salía con los nombres
     * de las claves y solo un aviso por consola lo decía.
     */
    private static void sinLlamarABase() {
        Messages t = Messages.from("textos", "en", "qu");

        Check.equal("el idioma base responde sin tener que declararlo",
                t.get("es", "saludo", "Richar"), "Hola, Richar");
        Check.that("y aparece entre los idiomas cargados", t.idiomas().contains("es"));
        Check.that("sin dejar la clave vacía suelta", !t.idiomas().contains(""));
        Check.equal("los demás idiomas siguen en su sitio",
                t.get("qu", "saludo", "Richar"), "Rimaykullayki, Richar");
    }

    /** Y si el archivo sin sufijo no está en castellano, `base(...)` lo mueve sin perder nada. */
    private static void baseEnOtroIdioma() {
        Messages t = Messages.from("textos", "en", "qu").base("qu");

        Check.equal("el archivo sin sufijo pasa a llamarse como se le diga",
                t.get("qu", "faltaEnIngles"), "Solo está en castellano");
        Check.equal("y lo que ya tenía ese nombre manda sobre él",
                t.get("qu", "saludo", "Richar"), "Rimaykullayki, Richar");
        Check.equal("el nuevo base es el último recurso de los demás",
                t.get("en", "faltaEnIngles"), "Solo está en castellano");
    }

    private static void sinRed() {
        Messages t = Messages.from("textos", "en", "qu").base("es");

        Check.equal("el idioma base pone sus argumentos", t.get("es", "saludo", "Richar"), "Hola, Richar");
        Check.equal("y cada idioma el suyo", t.get("en", "saludo", "Richar"), "Hello, Richar");
        Check.equal("incluido el quechua", t.get("qu", "saludo", "Richar"), "Rimaykullayki, Richar");

        // Los archivos van en UTF-8 y llevan acentos: si se leyeran en ISO-8859-1 esto rompe.
        Check.equal("los acentos sobreviven al .properties",
                t.get("es", "faltaEnIngles"), "Solo está en castellano");

        Check.equal("una clave que falta en un idioma cae al base",
                t.get("en", "faltaEnIngles"), "Solo está en castellano");
        Check.equal("y una clave que no existe en ninguno se devuelve tal cual",
                t.get("es", "no.hay"), "no.hay");

        // MessageFormat trata la comilla simple como escape. Si se formateara un texto sin
        // argumentos, «Vamos allá» sobreviviría pero un «d'accord» perdería la comilla.
        Check.equal("un texto sin argumentos no pasa por MessageFormat",
                t.get("es", "apostrofe"), "Vamos allá");

        Check.equal("negocia el idioma preferido", t.negociar("en-US,en;q=0.9,es;q=0.8"), "en");
        Check.equal("respeta el factor de calidad, no el orden",
                t.negociar("fr;q=0.2,qu;q=0.9"), "qu");
        Check.equal("un idioma regional cae en su idioma", t.negociar("es-PE"), "es");
        Check.equal("si nada encaja, el base", t.negociar("de,ja"), "es");
        Check.equal("sin cabecera, el base", t.negociar(null), "es");
        Check.equal("un idioma con q=0 se descarta", t.negociar("en;q=0,qu;q=0.5"), "qu");
    }

    private static void porHttp() throws Exception {
        Server servidor = Cero.app().port(0).quiet().reporter(ErrorReporter.silent())
                .messages(Messages.from("textos", "en", "qu").base("es"))
                .controllers(SaludosController.class)
                .start();
        String base = "http://127.0.0.1:" + servidor.port();
        try {
            Check.equal("sin Accept-Language responde en el idioma base",
                    Cliente.get(base + "/saludos").body(), "Hola, Richar");
            Check.equal("con Accept-Language responde en ese idioma",
                    Cliente.get(base + "/saludos", "Accept-Language", "en").body(), "Hello, Richar");
            Check.equal("y el contexto expone el idioma negociado",
                    Cliente.get(base + "/saludos/idioma", "Accept-Language", "qu-PE,qu;q=0.9").body(), "qu");
            Check.equal("una clave sin traducir cae al base sin romper la página",
                    Cliente.get(base + "/saludos/incompleto", "Accept-Language", "en").body(),
                    "Solo está en castellano");

            HttpResponse<String> inventado = Cliente.get(base + "/saludos/inventado");
            Check.equal("una clave inexistente devuelve la clave, no un 500",
                    inventado.statusCode(), 200);
            Check.equal("y el cuerpo es la propia clave",
                    inventado.body(), "no.existe.esta.clave");
        } finally {
            servidor.stop();
        }

        // Sin messages() configurado, t() no puede tumbar la aplicación.
        Server pelado = Cero.app().port(0).quiet().reporter(ErrorReporter.silent())
                .controllers(SaludosController.class)
                .start();
        try {
            HttpResponse<String> r = Cliente.get("http://127.0.0.1:" + pelado.port() + "/saludos");
            Check.equal("sin textos configurados, t() no lanza", r.statusCode(), 200);
            Check.equal("y devuelve la clave", r.body(), "saludo");
        } finally {
            pelado.stop();
        }
    }
}
