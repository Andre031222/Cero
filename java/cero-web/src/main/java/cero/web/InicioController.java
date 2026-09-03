package cero.web;

import cero.core.Context;
import cero.core.Get;
import cero.core.Inject;
import cero.core.Json;
import cero.core.Metrics;
import cero.core.Result;
import cero.core.Route;
import cero.http.HttpException;
import cero.http.Sse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Route("/")
public class InicioController {

    @Inject
    Metrics metricas;

    private static final long ARRANQUE = System.currentTimeMillis();

    @Get("")
    public Result index(Context contexto) {
        return pagina(contexto, "inicio", "Cero — Framework Java");
    }

    @Get("/descargas")
    public Result descargas(Context contexto) {
        return pagina(contexto, "descargas", "Descargas · Cero");
    }

    @Get("/empezar")
    public Result empezar(Context contexto) {
        return pagina(contexto, "empezar", "Empezar · Cero");
    }

    @Get("/guia")
    public Result guia(Context contexto) {
        return pagina(contexto, "guia", "Guía · Cero");
    }

    @Get("/modulos")
    public Result modulos(Context contexto) {
        return pagina(contexto, "modulos", "Módulos · Cero");
    }

    @Get("/referencia")
    public Result referencia(Context contexto) {
        return pagina(contexto, "referencia", "Referencia · Cero");
    }

    // ── inglés ──────────────────────────────────────────────────────────────
    // Mismas páginas bajo /en. El nombre del archivo no se traduce (/en/guia, no /en/guide):
    // así cada página y su pareja se corresponden sin tabla de equivalencias.

    @Get("/en")
    public Result indexEn(Context contexto) {
        return pagina(contexto, "en/inicio", "Cero — Java framework");
    }

    @Get("/en/descargas")
    public Result descargasEn(Context contexto) {
        return pagina(contexto, "en/descargas", "Downloads · Cero");
    }

    @Get("/en/empezar")
    public Result empezarEn(Context contexto) {
        return pagina(contexto, "en/empezar", "Get started · Cero");
    }

    @Get("/en/guia")
    public Result guiaEn(Context contexto) {
        return pagina(contexto, "en/guia", "Guide · Cero");
    }

    @Get("/en/modulos")
    public Result modulosEn(Context contexto) {
        return pagina(contexto, "en/modulos", "Modules · Cero");
    }

    @Get("/en/referencia")
    public Result referenciaEn(Context contexto) {
        return pagina(contexto, "en/referencia", "Reference · Cero");
    }

    @Get("/panel")
    public Result panel(Context contexto) {
        return pagina(contexto, "panel", "Panel en vivo · Cero");
    }

    /**
     * El mismo dato que /panel-data, pero empujado. El hilo se queda aquí mientras el panel esté
     * abierto, y eso solo sale barato porque cada conexión tiene su propio hilo virtual: cien
     * paneles abiertos son cien hilos virtuales, no cien del sistema.
     */
    @Get("/panel-eventos")
    public void panelEventos(Context contexto) throws InterruptedException {
        try (Sse eventos = Sse.open(contexto.response())) {
            eventos.reintentarEn(3000);
            while (eventos.abierto()) {
                eventos.send(Json.write(estadoDelPanel()));
                Thread.sleep(1000);
            }
        }
    }

    @Get("/panel-data")
    public Result panelDatos() {
        return Result.json(estadoDelPanel());
    }

    private Map<String, Object> estadoDelPanel() {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("uptimeMs", System.currentTimeMillis() - ARRANQUE);
        cuerpo.put("metrics", metricas == null ? Map.of() : metricas.snapshot());
        return cuerpo;
    }

    @Get("/datos")
    public Result baseDeDatos(Context contexto) {
        List<Map<String, Object>> filas = List.of(
                fila(1, "Alice", "Admin"),
                fila(2, "Bruno", "Editor"),
                fila(3, "Carla", "Viewer"));

        Map<String, Object> modelo = Vista.modelo(contexto, "Datos · Cero");
        modelo.put("estadoConexion", "Conectado");
        modelo.put("filas", filas);
        modelo.put("hayFilas", !filas.isEmpty());
        return Result.view("bd", modelo);
    }

    @Get("/errores")
    public Result errores(Context contexto) {
        return pagina(contexto, "errores", "Pruebas de errores · Cero");
    }

    @Get("/ping")
    public Result ping() {
        return Result.text("pong!");
    }

    @Get("/grabar/{valor}")
    public Result grabar(Context contexto, String valor) {
        Map<String, Object> modelo = Vista.modelo(contexto, "Argumentos de ruta · Cero");
        modelo.put("ruta", "/grabar/" + valor);
        modelo.put("valor", valor);
        return Result.view("grabar", modelo);
    }

    @Get("/error403")
    public Result error403() {
        throw new HttpException(403, "Acceso denegado — recurso protegido (demostración)");
    }

    @Get("/error500")
    public Result error500() {
        throw new IllegalStateException("Demostración interna para probar el error 500");
    }

    private Result pagina(Context contexto, String plantilla, String titulo) {
        return Result.view(plantilla, Vista.modelo(contexto, titulo));
    }

    private static Map<String, Object> fila(int id, String nombre, String rol) {
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("id", id);
        fila.put("nombre", nombre);
        fila.put("rol", rol);
        return fila;
    }
}
