package cero.adapter.servlet;

import cero.core.Body;
import cero.core.Context;
import cero.core.CookieValue;
import cero.core.Delete;
import cero.core.Form;
import cero.core.Get;
import cero.core.Header;
import cero.core.Cero;
import cero.core.Path;
import cero.core.Post;
import cero.core.Query;
import cero.core.Result;
import cero.core.Route;
import cero.core.Valid;
import cero.core.Required;
import cero.http.ErrorReporter;
import cero.http.HttpException;

public final class TestSuite {

    private static int passed;
    private static int failed;

    private TestSuite() {
    }

    public record Nota(@Required String titulo) {
    }

    @Route("/api")
    public static final class ApiController {

        @Get
        public Object index() {
            return "raíz";
        }

        @Get("/texto")
        public String texto() {
            return "en texto plano";
        }

        @Get("/notas/{id}")
        public Object porId(@Path("id") int id) {
            if (id > 100) {
                throw new HttpException(404, "no existe");
            }
            return new Nota("nota " + id);
        }

        @Get("/buscar")
        public Object buscar(@Query("q") String q, @Query(value = "p", orElse = "1") int p) {
            return q + "|" + p;
        }

        @Get("/cabecera")
        public Object cabecera(@Header("X-Prueba") String valor) {
            return valor == null ? "sin cabecera" : valor;
        }

        @Get("/galleta")
        public Object galleta(@CookieValue("sabor") String sabor) {
            return sabor == null ? "sin galleta" : sabor;
        }

        @Get("/contexto")
        public Object contexto(Context ctx) {
            return ctx.method() + " " + ctx.path() + " desde " + ctx.request().remoteAddress()
                    + (ctx.request().secure() ? " seguro" : " plano");
        }

        @Get("/sesion")
        public Object sesion(Context ctx) {
            var s = ctx.session();
            Object visitas = s.get("visitas");
            int n = visitas == null ? 1 : (int) visitas + 1;
            s.set("visitas", n);
            return "visita " + n;
        }

        @Post("/formulario")
        public Object formulario(@Form("titulo") String titulo) {
            return "recibido: " + titulo;
        }

        @Post("/notas")
        public Object crear(@Body @Valid Nota nota) {
            return Result.created(nota).header("Location", "/api/notas/1");
        }

        @Get("/vacio")
        public Object vacio() {
            return Result.noContent();
        }

        @Get("/mudanza")
        public Object mudanza() {
            return Result.redirect("/api/texto");
        }

        @Delete("/notas/{id}")
        public Object borrar(@Path("id") int id) {
            return Result.noContent();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("── adaptador de servlet");

        CeroServlet servlet = new CeroServlet(Cero.app()
                .quiet()
                .reporter(ErrorReporter.silent())
                .controllers(ApiController.class));

        enrutado(servlet);
        parametros(servlet);
        sesionYGalletas(servlet);
        respuestas(servlet);
        errores(servlet);
        contextPath(servlet);

        System.out.println();
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("  TOTAL  pass=%d  fail=%d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void enrutado(CeroServlet servlet) throws Exception {
        Doble.Respuesta r = llamar(servlet, peticion("GET", "/api"));
        check("la raíz del controlador responde 200", r.estado == 200);
        check("con el cuerpo", r.texto().equals("raíz"));
        check("y el tipo de texto plano", r.contentType.startsWith("text/plain"));
        check("declara la longitud", r.contentLength != null && r.contentLength == 5);

        check("una ruta inexistente da 404",
                llamar(servlet, peticion("GET", "/api/nada")).estado == 404);

        Doble.Respuesta noPermitido = llamar(servlet, peticion("DELETE", "/api/texto"));
        check("un verbo no permitido da 405", noPermitido.estado == 405);
        check("y anuncia los verbos válidos",
                String.valueOf(noPermitido.cabeceras.get("Allow")).contains("GET"));
    }

    private static void parametros(CeroServlet servlet) throws Exception {
        check("la variable de ruta se convierte",
                llamar(servlet, peticion("GET", "/api/notas/7")).texto().contains("nota 7"));

        Doble.Peticion conConsulta = peticion("GET", "/api/buscar");
        conConsulta.consulta = "q=cero&p=4";
        check("los parámetros de consulta llegan",
                llamar(servlet, conConsulta).texto().equals("cero|4"));

        Doble.Peticion sinPagina = peticion("GET", "/api/buscar");
        sinPagina.consulta = "q=cero";
        check("y el valor por defecto se aplica",
                llamar(servlet, sinPagina).texto().equals("cero|1"));

        Doble.Peticion conCabecera = peticion("GET", "/api/cabecera");
        conCabecera.cabecera("X-Prueba", "valor");
        check("la cabecera se vincula", llamar(servlet, conCabecera).texto().equals("valor"));
        check("y su ausencia queda en null",
                llamar(servlet, peticion("GET", "/api/cabecera")).texto().equals("sin cabecera"));

        Doble.Peticion formulario = peticion("POST", "/api/formulario");
        formulario.cabecera("Content-Type", "application/x-www-form-urlencoded");
        formulario.cuerpo("titulo=Desde+el+formulario");
        check("el formulario se parsea",
                llamar(servlet, formulario).texto().equals("recibido: Desde el formulario"));

        Doble.Peticion json = peticion("POST", "/api/notas");
        json.cabecera("Content-Type", "application/json");
        json.cuerpo("{\"titulo\":\"Desde JSON\"}");
        Doble.Respuesta creada = llamar(servlet, json);
        check("el cuerpo JSON se vincula al record", creada.estado == 201);
        check("y devuelve el recurso", creada.texto().contains("Desde JSON"));
        check("con su cabecera Location", "/api/notas/1".equals(creada.cabeceras.get("Location")));

        Doble.Peticion invalido = peticion("POST", "/api/notas");
        invalido.cabecera("Content-Type", "application/json");
        invalido.cuerpo("{\"titulo\":\"\"}");
        Doble.Respuesta rechazada = llamar(servlet, invalido);
        check("la validación responde 422", rechazada.estado == 422);
        check("con el mapa de campos", rechazada.texto().contains("\"titulo\""));
    }

    private static void sesionYGalletas(CeroServlet servlet) throws Exception {
        Doble.Peticion conGalleta = peticion("GET", "/api/galleta");
        conGalleta.galleta("sabor", "vainilla");
        check("la cookie se lee del contenedor",
                llamar(servlet, conGalleta).texto().equals("vainilla"));
        check("y su ausencia se distingue",
                llamar(servlet, peticion("GET", "/api/galleta")).texto().equals("sin galleta"));

        Doble.Peticion primera = peticion("GET", "/api/sesion");
        check("la sesión arranca en 1", llamar(servlet, primera).texto().equals("visita 1"));

        Doble.Peticion segunda = peticion("GET", "/api/sesion");
        segunda.sesion = primera.sesion;
        segunda.sesion.nueva = false;
        check("y conserva el atributo entre peticiones",
                llamar(servlet, segunda).texto().equals("visita 2"));
        check("el atributo vive en la sesión del contenedor",
                primera.sesion.atributos.get("visitas").equals(2));
    }

    private static void respuestas(CeroServlet servlet) throws Exception {
        Doble.Respuesta vacio = llamar(servlet, peticion("GET", "/api/vacio"));
        check("noContent da 204", vacio.estado == 204);
        check("y no escribe cuerpo", vacio.texto().isEmpty());

        Doble.Respuesta mudanza = llamar(servlet, peticion("GET", "/api/mudanza"));
        check("redirect da 302", mudanza.estado == 302);
        check("con Location", "/api/texto".equals(mudanza.cabeceras.get("Location")));

        Doble.Respuesta objeto = llamar(servlet, peticion("GET", "/api/notas/3"));
        check("un objeto sale como JSON", objeto.contentType.startsWith("application/json"));

        Doble.Peticion segura = peticion("GET", "/api/contexto");
        segura.segura = true;
        String contexto = llamar(servlet, segura).texto();
        check("el contexto ve el método y la ruta", contexto.startsWith("GET /api/contexto"));
        check("la IP del cliente llega", contexto.contains("10.0.0.7"));
        check("y el indicador de conexión segura", contexto.endsWith("seguro"));
    }

    private static void errores(CeroServlet servlet) throws Exception {
        Doble.Respuesta noEsta = llamar(servlet, peticion("GET", "/api/notas/999"));
        check("HttpException conserva el estado", noEsta.estado == 404);
        check("y responde JSON con la ruta", noEsta.texto().contains("/api/notas/999"));
    }

    private static void contextPath(CeroServlet servlet) throws Exception {
        Doble.Peticion desplegada = peticion("GET", "/miapp/api/texto");
        desplegada.contextPath = "/miapp";
        check("el contexto de despliegue se recorta de la ruta",
                llamar(servlet, desplegada).texto().equals("en texto plano"));

        Doble.Peticion raiz = peticion("GET", "/miapp");
        raiz.contextPath = "/miapp";
        check("y la raíz del contexto queda en /", llamar(servlet, raiz).estado == 404);
    }

    private static Doble.Peticion peticion(String metodo, String uri) {
        Doble.Peticion p = new Doble.Peticion();
        p.metodo = metodo;
        p.uri = uri;
        return p;
    }

    private static Doble.Respuesta llamar(CeroServlet servlet, Doble.Peticion peticion)
            throws Exception {
        Doble.Respuesta respuesta = new Doble.Respuesta();
        servlet.service(peticion.construir(), respuesta.construir());
        return respuesta;
    }

    private static void check(String nombre, boolean condicion) {
        if (condicion) {
            passed++;
            System.out.println("  OK  " + nombre);
        } else {
            failed++;
            System.out.println("  XX  " + nombre);
        }
    }
}
