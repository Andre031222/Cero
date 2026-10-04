// Una prueba por requisito de `spec/seguridad.md`, citándolo.

#include <chrono>
#include <optional>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

#include "cero/seguridad.hpp"
#include "prueba.hpp"

namespace {

using namespace std::chrono_literals;

std::optional<std::string> tiene(const cero::Pares& h, std::string_view nombre) {
    for (const auto& [n, v] : h) {
        if (n == nombre) return v;
    }
    return std::nullopt;
}

// ── Cabeceras ───────────────────────────────────────────────────────────────

PRUEBA(seg_001_a_004_las_cabeceras_por_defecto) {
    const auto h = cero::Proteccion{}.aplicar(false);
    COMPRUEBA(tiene(h, "X-Content-Type-Options") == "nosniff", "SEG-001");
    COMPRUEBA(tiene(h, "X-Frame-Options") == "DENY", "SEG-002");
    COMPRUEBA(tiene(h, "Referrer-Policy").has_value(), "SEG-003");
    const auto permisos = tiene(h, "Permissions-Policy").value_or("");
    for (std::string_view capacidad : {"camera=()", "microphone=()", "geolocation=()"}) {
        COMPRUEBA(permisos.find(capacidad) != std::string::npos, "SEG-004");
    }
}

PRUEBA(seg_005_sin_tls_no_se_promete_tls) {
    COMPRUEBA(!tiene(cero::Proteccion{}.aplicar(false), "Strict-Transport-Security").has_value(),
              "SEG-005");
    COMPRUEBA(tiene(cero::Proteccion{}.aplicar(true), "Strict-Transport-Security").has_value(),
              "SEG-005: con TLS sí");
}

PRUEBA(seg_006_y_007_la_csp_y_el_enmarcado) {
    COMPRUEBA(!tiene(cero::Proteccion{}.aplicar(true), "Content-Security-Policy").has_value(),
              "SEG-006: no se inventa una");
    const cero::Proteccion con{"SAMEORIGIN", "default-src 'self'"};
    const auto h = con.aplicar(false);
    COMPRUEBA(tiene(h, "Content-Security-Policy") == "default-src 'self'", "SEG-007");
    COMPRUEBA(tiene(h, "X-Frame-Options") == "SAMEORIGIN", "SEG-007");
    COMPRUEBA(tiene(h, "X-Content-Type-Options") == "nosniff", "SEG-007: y sin tocar el resto");
}

// ── CORS ────────────────────────────────────────────────────────────────────

PRUEBA(seg_008_y_009_un_origen_permitido) {
    const cero::Cors c{{"https://mio.dev"}};
    const auto d = c.decidir("GET", "https://mio.dev");
    const auto* sigue = std::get_if<cero::Cors::Sigue>(&d);
    COMPRUEBA(sigue != nullptr, "SEG-008: la petición simple sigue su curso");
    COMPRUEBA(sigue && tiene(sigue->cabeceras, "Access-Control-Allow-Origin") == "https://mio.dev",
              "SEG-008");
    COMPRUEBA(sigue && tiene(sigue->cabeceras, "Vary") == "Origin", "SEG-009");
}

PRUEBA(seg_010_y_011_el_origen_ajeno_y_el_ausente) {
    const cero::Cors c{{"https://mio.dev"}};
    const auto ajeno = c.decidir("GET", "https://otro.dev");
    const auto* sigue = std::get_if<cero::Cors::Sigue>(&ajeno);
    COMPRUEBA(sigue != nullptr, "SEG-010: la petición simple no se bloquea");
    COMPRUEBA(sigue && !tiene(sigue->cabeceras, "Access-Control-Allow-Origin").has_value(),
              "SEG-010: pero sin permiso");

    const auto sin = c.decidir("GET", std::nullopt);
    const auto* vacio = std::get_if<cero::Cors::Sigue>(&sin);
    COMPRUEBA(vacio && vacio->cabeceras.empty(), "SEG-011");
}

PRUEBA(seg_012_y_013_los_preflight) {
    const cero::Cors c{{"https://mio.dev"}};
    const auto bueno = c.decidir("OPTIONS", "https://mio.dev");
    const auto* corta = std::get_if<cero::Cors::Corta>(&bueno);
    COMPRUEBA(corta && corta->estado == 204, "SEG-012");
    COMPRUEBA(corta && tiene(corta->cabeceras, "Access-Control-Allow-Methods").has_value(),
              "SEG-012");
    COMPRUEBA(corta && tiene(corta->cabeceras, "Access-Control-Allow-Headers").has_value(),
              "SEG-012");
    COMPRUEBA(corta && tiene(corta->cabeceras, "Access-Control-Max-Age").has_value(), "SEG-012");

    const auto malo = c.decidir("OPTIONS", "https://otro.dev");
    const auto* ajeno = std::get_if<cero::Cors::Corta>(&malo);
    COMPRUEBA(ajeno && ajeno->estado == 403, "SEG-013: aquí sí decide el servidor");
}

PRUEBA(seg_014_el_comodin_y_las_credenciales) {
    const auto sin = cero::Cors{}.decidir("GET", "https://cualquiera.dev");
    const auto* a = std::get_if<cero::Cors::Sigue>(&sin);
    COMPRUEBA(a && tiene(a->cabeceras, "Access-Control-Allow-Origin") == "*", "SEG-014");

    cero::Cors con;
    con.credenciales = true;
    const auto d = con.decidir("GET", "https://cualquiera.dev");
    const auto* b = std::get_if<cero::Cors::Sigue>(&d);
    // El navegador rechaza `*` junto a credenciales, así que el comodín deja de valer.
    COMPRUEBA(b && tiene(b->cabeceras, "Access-Control-Allow-Origin") == "https://cualquiera.dev",
              "SEG-014");
    COMPRUEBA(b && tiene(b->cabeceras, "Access-Control-Allow-Credentials") == "true", "SEG-014");
}

// ── CSRF ────────────────────────────────────────────────────────────────────

PRUEBA(seg_015_a_018_el_token) {
    COMPRUEBA(cero::csrf_valido("GET", "/a", {}, std::nullopt, std::nullopt), "SEG-015");
    COMPRUEBA(!cero::csrf_valido("POST", "/a", {}, std::nullopt, std::nullopt), "SEG-016");
    COMPRUEBA(!cero::csrf_valido("POST", "/a", {}, "esperado", std::nullopt), "SEG-016");
    COMPRUEBA(cero::csrf_valido("POST", "/a", {}, "esperado", "esperado"), "SEG-018");
    COMPRUEBA(!cero::csrf_valido("POST", "/a", {}, "esperado", "otro"), "SEG-018");
    COMPRUEBA(!cero::csrf_valido("POST", "/a", {}, "esperado", "esperadoooo"),
              "SEG-018: ni un prefijo");
}

PRUEBA(seg_019_la_exencion_casa_por_segmento) {
    const std::vector<std::string> exentas{"/api/publico"};
    COMPRUEBA(cero::exento("/api/publico", exentas), "SEG-019");
    COMPRUEBA(cero::exento("/api/publico/dentro", exentas), "SEG-019: y lo que cuelga");
    // El hallazgo de auditoría que dio origen al requisito: con prefijo pelado, eximir
    // `/api/publico` eximía también `/api/publicoSECRETO`.
    COMPRUEBA(!cero::exento("/api/publicoSECRETO", exentas), "SEG-019");
    COMPRUEBA(!cero::exento("/api/privado", exentas), "SEG-019");
}

// ── Límite de peticiones ────────────────────────────────────────────────────

PRUEBA(seg_020_y_021_lo_que_anuncia_el_limite) {
    cero::Limitador l{2, 60s};
    const auto primera = l.pedir("1.2.3.4");
    COMPRUEBA(primera.permitida && primera.restante == 1, "SEG-021");
    COMPRUEBA(l.pedir("1.2.3.4").restante == 0, "SEG-021");

    const auto pasada = l.pedir("1.2.3.4");
    COMPRUEBA(!pasada.permitida, "SEG-020");
    const auto h = cero::cabeceras_limite(pasada);
    COMPRUEBA(tiene(h, "Retry-After").has_value(), "SEG-020");
    COMPRUEBA(tiene(h, "X-RateLimit-Remaining") == "0", "SEG-020: y sin cupo restante");
    COMPRUEBA(tiene(h, "X-RateLimit-Limit") == "2", "SEG-021");
}

PRUEBA(seg_022_la_cuota_no_depende_de_la_ruta) {
    // El fallo que dio origen al requisito tenía dos caras: con la ruta en la clave, cambiar de
    // camino daba cupo nuevo, y además el mapa crecía sin tope con rutas inventadas. No era solo
    // un límite esquivable, era agotamiento de memoria.
    cero::Limitador l{2, 60s};
    (void)l.pedir("1.2.3.4");
    (void)l.pedir("1.2.3.4");
    COMPRUEBA(!l.pedir("1.2.3.4").permitida, "SEG-022");
    COMPRUEBA(l.claves() == 1, "SEG-022: una clave por cliente, no por ruta");
    COMPRUEBA(l.pedir("5.6.7.8").permitida, "y otro cliente tiene la suya");
}

// ── Saneado ─────────────────────────────────────────────────────────────────

PRUEBA(seg_023_y_024_el_saneado_de_html) {
    const auto limpio = cero::sanear_html("<p>hola</p><script>robar()</script><b>mundo</b>");
    COMPRUEBA(limpio.find("script") == std::string::npos, "SEG-023");
    COMPRUEBA(limpio.find("robar") == std::string::npos,
              "SEG-023: y su contenido, o otro contexto lo vuelve a ejecutar");
    // SEG-024: uno que borra todo se desactiva, y entonces no sanea nada.
    COMPRUEBA(limpio.find("<p>") != std::string::npos, "SEG-024");
    COMPRUEBA(limpio.find("<b>") != std::string::npos, "SEG-024");
    COMPRUEBA(limpio.find("mundo") != std::string::npos, "SEG-024");

    for (std::string_view etiqueta : {"<style>x</style>", "<iframe src=x></iframe>"}) {
        COMPRUEBA(cero::sanear_html(etiqueta).find('<') == std::string::npos, "SEG-023");
    }
}

PRUEBA(seg_023_las_dos_formas_de_ejecutar_sin_script) {
    const auto evento = cero::sanear_html("<img src=x onerror=\"robar()\">");
    COMPRUEBA(evento.find("onerror") == std::string::npos, "SEG-023: manejadores de evento");
    COMPRUEBA(evento.find("img") != std::string::npos, "SEG-024: la etiqueta se queda");

    const auto protocolo = cero::sanear_html("<a href=\"javascript:robar()\">pulsa</a>");
    COMPRUEBA(protocolo.find("javascript:") == std::string::npos, "SEG-023: y el protocolo");
}

PRUEBA(seg_025_el_saneado_a_texto_plano) {
    const auto plano = cero::sanear_texto("<p>hola</p><script>robar()</script>");
    COMPRUEBA(plano.find('<') == std::string::npos, "SEG-025");
    COMPRUEBA(plano.find("robar") == std::string::npos, "SEG-025: sin rastro del script");
    COMPRUEBA(plano.find("hola") != std::string::npos, "y el texto inocuo se queda");
}

PRUEBA(seg_026_el_saneado_de_nombres_de_archivo) {
    COMPRUEBA(cero::sanear_nombre("../../etc/passwd") == "passwd", "SEG-026");
    COMPRUEBA(cero::sanear_nombre("C:\\Windows\\system32\\x.dll") == "x.dll",
              "SEG-026: los dos sistemas");
    COMPRUEBA(cero::sanear_nombre("informe final.bin") == "informe final.bin", "lo inocuo pasa");
    // Nunca vacío: un nombre vacío en una cabecera `Content-Disposition` la deja a medias.
    for (std::string_view malo : {"", "...", "/", "   ", "\\\\"}) {
        COMPRUEBA(!cero::sanear_nombre(malo).empty(), "SEG-026");
    }
    const auto trampa = cero::sanear_nombre("a\"b\r\nSet-Cookie: sesion=robada");
    COMPRUEBA(trampa.find('\r') == std::string::npos && trampa.find('\n') == std::string::npos,
              "SEG-026: por aquí se intentó colar una cookie");
}

}  // namespace

PRUEBAS_MAIN
