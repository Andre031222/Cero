package lux.web;

import lux.core.Context;
import lux.core.Get;
import lux.core.Inject;
import lux.core.Metrics;
import lux.core.Result;
import lux.core.Route;
import lux.http.HttpException;

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
        return pagina(contexto, "inicio", "LuxCore — Framework Java");
    }

    @Get("/descargas")
    public Result descargas(Context contexto) {
        return pagina(contexto, "descargas", "Descargas · LuxCore");
    }

    @Get("/empezar")
    public Result empezar(Context contexto) {
        return pagina(contexto, "empezar", "Empezar · LuxCore");
    }

    @Get("/guia")
    public Result guia(Context contexto) {
        return pagina(contexto, "guia", "Guía · LuxCore");
    }

    @Get("/modulos")
    public Result modulos(Context contexto) {
        return pagina(contexto, "modulos", "Módulos · LuxCore");
    }

    @Get("/referencia")
    public Result referencia(Context contexto) {
        return pagina(contexto, "referencia", "Referencia · LuxCore");
    }

    @Get("/panel")
    public Result panel(Context contexto) {
        return pagina(contexto, "panel", "Panel en vivo · LuxCore");
    }

    @Get("/panel-data")
    public Result panelDatos() {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("uptimeMs", System.currentTimeMillis() - ARRANQUE);
        cuerpo.put("metrics", metricas == null ? Map.of() : metricas.snapshot());
        return Result.json(cuerpo);
    }

    @Get("/datos")
    public Result baseDeDatos(Context contexto) {
        List<Map<String, Object>> filas = List.of(
                fila(1, "Alice", "Admin"),
                fila(2, "Bruno", "Editor"),
                fila(3, "Carla", "Viewer"));

        Map<String, Object> modelo = Vista.modelo(contexto, "Datos · LuxCore");
        modelo.put("estadoConexion", "Conectado");
        modelo.put("filas", filas);
        modelo.put("hayFilas", !filas.isEmpty());
        return Result.view("bd", modelo);
    }

    @Get("/errores")
    public Result errores(Context contexto) {
        return pagina(contexto, "errores", "Pruebas de errores · LuxCore");
    }

    @Get("/ping")
    public Result ping() {
        return Result.text("pong!");
    }

    @Get("/grabar/{valor}")
    public Result grabar(Context contexto, String valor) {
        Map<String, Object> modelo = Vista.modelo(contexto, "Argumentos de ruta · LuxCore");
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
