// Una prueba por requisito de `spec/observabilidad.md`, citándolo.

#include <chrono>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>

#include "cero/observabilidad.hpp"
#include "prueba.hpp"

namespace {

using namespace std::chrono_literals;

bool dice(const std::string& cuerpo, std::string_view que) {
    return cuerpo.find(que) != std::string::npos;
}

// ── Salud ───────────────────────────────────────────────────────────────────

PRUEBA(obs_001_la_vida_no_depende_de_nadie) {
    cero::Salud s;
    s.comprobacion("base", [] { return cero::Comprobado::falla("caída"); });
    const auto v = s.vivo();
    // Si la vida admitiera comprobaciones, un supervisor reiniciaría el proceso por una base de
    // datos lenta: cambiaría un problema pasajero por una caída.
    COMPRUEBA(v.estado == 200, "OBS-001");
    COMPRUEBA(dice(v.cuerpo, "activo_s"), "OBS-001: e informa cuánto lleva en pie");
}

PRUEBA(obs_002_la_disponibilidad_nombra_lo_que_comprueba) {
    cero::Salud s;
    s.comprobacion("base", [] { return cero::Comprobado::va(); });
    s.comprobacion("cache", [] { return cero::Comprobado::va(); });
    const auto l = s.listo();
    COMPRUEBA(l.estado == 200, "OBS-002");
    COMPRUEBA(dice(l.cuerpo, "base") && dice(l.cuerpo, "cache"), "OBS-002");
}

PRUEBA(obs_003_y_004_una_caida_no_toca_la_vida) {
    cero::Salud s;
    s.comprobacion("base", [] { return cero::Comprobado::falla("sin conexión"); });
    s.comprobacion("cache", [] { return cero::Comprobado::va(); });

    COMPRUEBA(s.vivo().estado == 200, "OBS-003: la vida sigue en 200");
    const auto l = s.listo();
    COMPRUEBA(l.estado == 503, "OBS-003");
    COMPRUEBA(dice(l.cuerpo, "base") && dice(l.cuerpo, "sin conexión"), "OBS-004: dice cuál falló");
    COMPRUEBA(dice(l.cuerpo, "cache"), "OBS-004: sin ocultar las que sí van");
}

PRUEBA(obs_005_una_comprobacion_que_lanza_da_503) {
    cero::Salud s;
    s.comprobacion("base", []() -> cero::Comprobado { throw std::runtime_error("reventó"); });
    // Lanzar es una forma de fallar, no un fallo del endpoint. Un 500 aquí diría que el problema
    // es el endpoint de salud, y mandaría a mirar donde no es.
    COMPRUEBA(s.listo().estado == 503, "OBS-005");
}

PRUEBA(obs_006_y_007_el_modo_publico) {
    cero::Salud s;
    s.publico = true;
    s.comprobacion("base-de-datos-interna", [] { return cero::Comprobado::falla("clave caducada"); });

    const auto mal = s.listo();
    COMPRUEBA(mal.estado == 503, "OBS-006: el código no cambia");
    COMPRUEBA(!dice(mal.cuerpo, "base-de-datos-interna"), "OBS-006: ni el nombre");
    COMPRUEBA(!dice(mal.cuerpo, "clave caducada"), "OBS-006: ni el motivo");

    cero::Salud bien;
    bien.publico = true;
    bien.comprobacion("base", [] { return cero::Comprobado::va(); });
    const auto l = bien.listo();
    COMPRUEBA(l.estado == 200 && dice(l.cuerpo, "listo"), "OBS-007");
    COMPRUEBA(!dice(l.cuerpo, "base"), "OBS-007: solo que está listo");
}

// ── Registro ────────────────────────────────────────────────────────────────

PRUEBA(obs_008_una_linea_lleva_nivel_origen_y_valores) {
    cero::Log log{"cero", cero::Nivel::Info};
    log.escribir(cero::Nivel::Aviso, "la ruta {} tardó {}ms", {"/notas", "41"});
    const auto l = log.lineas().at(0);
    COMPRUEBA(dice(l, "Aviso"), "OBS-008: el nivel");
    COMPRUEBA(dice(l, "cero"), "OBS-008: el origen");
    COMPRUEBA(dice(l, "/notas") && dice(l, "41"), "OBS-008: y los valores interpolados");
}

PRUEBA(obs_009_el_nivel_filtra_y_hay_uno_que_calla_todo) {
    cero::Log log{"cero", cero::Nivel::Aviso};
    log.escribir(cero::Nivel::Info, "por debajo");
    log.escribir(cero::Nivel::Error, "por encima");
    COMPRUEBA(log.lineas().size() == 1, "OBS-009");
    COMPRUEBA(dice(log.lineas().at(0), "por encima"), "OBS-009");

    cero::Log callado{"cero", cero::Nivel::Nada};
    callado.escribir(cero::Nivel::Error, "ni esto");
    COMPRUEBA(callado.lineas().empty(), "OBS-009: un nivel que calla todo");
}

PRUEBA(obs_010_un_error_lleva_su_tipo_y_su_mensaje) {
    cero::Log log{"cero", cero::Nivel::Info};
    log.con_error("la ruta {} no pudo responder", {"/notas"}, "FalloDeBase",
                  "la conexión se cayó");
    const auto l = log.lineas().at(0);
    COMPRUEBA(dice(l, "/notas"), "OBS-010: y sigue interpolando");
    COMPRUEBA(dice(l, "FalloDeBase"), "OBS-010: el tipo");
    COMPRUEBA(dice(l, "la conexión se cayó"), "OBS-010: y el mensaje");
}

PRUEBA(obs_011_interpolar_no_falla_nunca) {
    // Un log que revienta se lleva por delante justo lo que iba a contar, así que por aquí no
    // puede salir una excepción pase lo que pase.
    COMPRUEBA(cero::interpolar("{} y {}", {"uno"}) == "uno y {}", "OBS-011: de menos");
    COMPRUEBA(cero::interpolar("{}", {"uno", "dos"}) == "uno", "OBS-011: de más se ignoran");
    COMPRUEBA(cero::interpolar("sin marcadores", {"uno"}) == "sin marcadores", "OBS-011");
    COMPRUEBA(cero::interpolar("", {}).empty(), "OBS-011");
    COMPRUEBA(cero::interpolar("{}{}{}", {"a", "b"}) == "ab{}", "OBS-011");
}

// ── Métricas ────────────────────────────────────────────────────────────────

PRUEBA(obs_013_y_016_se_cuentan_las_peticiones_y_los_errores) {
    cero::Metricas m;
    m.anotar("/a", 200, 1000us);
    m.anotar("/a", 500, 1000us);
    m.anotar("/a", 404, 1000us);
    COMPRUEBA(m.total() == 3, "OBS-013");
    COMPRUEBA(m.peticiones("/a") == 3, "OBS-013");
    // Un 404 es entrada del cliente, pero también la señal de que alguien enlazó mal algo nuestro.
    COMPRUEBA(m.errores("/a") == 2, "OBS-016");
}

PRUEBA(obs_014_se_agrupa_por_patron_y_no_por_url) {
    cero::Metricas m;
    for (int i = 0; i < 100; ++i) m.anotar("/usuarios/{id}", 200, 1000us);
    COMPRUEBA(m.patrones() == 1, "OBS-014");
    COMPRUEBA(m.peticiones("/usuarios/{id}") == 100, "OBS-014");
}

PRUEBA(obs_015_la_latencia_va_por_percentiles) {
    cero::Metricas m;
    for (int i = 1; i <= 100; ++i) m.anotar("/a", 200, std::chrono::microseconds{i * 1000});
    // La media esconde la cola, que es donde vive el usuario que se queja. El percentil se toma
    // por rango más cercano —`round((n-1)·p)`— y no interpolando: con cien muestras de 1 a 100 ms
    // eso deja la mediana en 51 y el 99 en 99, que es lo mismo que da la implementación en Rust.
    COMPRUEBA(m.percentil("/a", 0.5) == 51'000, "OBS-015");
    COMPRUEBA(m.percentil("/a", 0.99) == 99'000, "OBS-015");
    COMPRUEBA(m.percentil("/a", 1.0) == 100'000, "OBS-015: y el tope es el peor caso de verdad");
    COMPRUEBA(!m.percentil("/sin-datos", 0.5).has_value(), "y sin datos no se inventa");
}

PRUEBA(obs_017_y_018_lo_ignorado_y_la_exposicion) {
    cero::Metricas m;
    m.ignorar("/cero/vivo");
    m.anotar("/cero/vivo", 200, 1000us);
    m.anotar("/a", 200, 1000us);
    COMPRUEBA(m.total() == 1, "OBS-017");
    COMPRUEBA(m.peticiones("/cero/vivo") == 0, "OBS-017");

    const auto j = m.json();
    COMPRUEBA(dice(j, "\"total\":1"), "OBS-018");
    COMPRUEBA(dice(j, "/a") && !dice(j, "/cero/vivo"), "OBS-018: el detalle por ruta");
}

// ── Log de acceso ───────────────────────────────────────────────────────────

PRUEBA(obs_019_a_021_la_linea_de_acceso) {
    const auto l = cero::linea_acceso("GET", "/notas?pagina=2", 200, std::nullopt, 41000us);
    COMPRUEBA(dice(l, "GET") && dice(l, "200"), "OBS-019");
    COMPRUEBA(dice(l, "/notas?pagina=2"), "OBS-020: la consulta se conserva");
    // Un campo vacío en una línea separada por espacios corre las columnas siguientes y desalinea
    // el fichero entero, así que el usuario ausente lleva marca.
    COMPRUEBA(dice(l, " - "), "OBS-021");
    COMPRUEBA(dice(cero::linea_acceso("GET", "/a", 200, "ana", 1000us), "ana"),
              "y el identificado va con su nombre");
}

PRUEBA(obs_022_los_errores_tambien_se_registran) {
    const auto l = cero::linea_acceso("POST", "/notas", 500, std::nullopt, 7000us);
    COMPRUEBA(dice(l, "500"), "OBS-022");
}

}  // namespace

PRUEBAS_MAIN
