// Una prueba por requisito de `spec/conformidad.md`, citándolo.
//
// Se parsea desde una cadena y no desde un socket: los mismos 23 vectores los corre después el
// banco contra el proceso de verdad, así que aquí lo que se gana es saber **cuál** falló.

#include "cero/peticion.hpp"
#include "prueba.hpp"

namespace {

std::expected<cero::Peticion, cero::Rechazo> parsea(std::string_view crudo) {
    cero::DesdeTexto lector{crudo};
    return cero::leer(lector);
}

unsigned estado(std::string_view crudo) {
    const auto p = parsea(crudo);
    return p ? 200 : cero::estado_de(p.error());
}

// ── Forma de la petición ────────────────────────────────────────────────────

PRUEBA(http_001_a_003_las_tres_formas_del_destino) {
    COMPRUEBA(estado("GET /ruta?q=1 HTTP/1.1\r\nHost: x\r\n\r\n") == 200, "HTTP-001");
    COMPRUEBA(estado("GET http://x/ruta HTTP/1.1\r\nHost: x\r\n\r\n") == 200, "HTTP-002");
    COMPRUEBA(estado("OPTIONS * HTTP/1.1\r\nHost: x\r\n\r\n") == 200, "HTTP-003");

    const auto absoluta = parsea("GET http://x/ruta?q=1 HTTP/1.1\r\nHost: x\r\n\r\n");
    COMPRUEBA(absoluta && absoluta->camino() == "/ruta", "HTTP-002: se reduce a su camino");
    const auto sin_barra = parsea("GET http://x HTTP/1.1\r\nHost: x\r\n\r\n");
    COMPRUEBA(sin_barra && sin_barra->camino() == "/", "HTTP-002: sin barra, la raíz");
}

PRUEBA(http_004_y_005_la_linea_inicial_y_la_version) {
    COMPRUEBA(estado("GET /ruta\r\nHost: x\r\n\r\n") == 400, "HTTP-004: sin versión es 400");
    COMPRUEBA(estado("GET /ruta HTTP/9.9\r\nHost: x\r\n\r\n") == 505, "HTTP-005");
    COMPRUEBA(estado("GET /a b HTTP/1.1\r\nHost: x\r\n\r\n") == 400, "cuatro partes tampoco");
}

PRUEBA(http_006_y_007_el_metodo_y_sus_mayusculas) {
    COMPRUEBA(estado("INVENTADO / HTTP/1.1\r\nHost: x\r\n\r\n") == 501, "HTTP-006: 501, no 400");
    COMPRUEBA(estado("get / HTTP/1.1\r\nHost: x\r\n\r\n") == 501, "HTTP-007");
}

PRUEBA(http_023_una_linea_desmedida) {
    std::string larga = "GET /";
    larga.append(20000, 'a');
    larga += " HTTP/1.1\r\nHost: x\r\n\r\n";
    COMPRUEBA(estado(larga) == 431, "HTTP-023");
}

// ── Cabeceras ───────────────────────────────────────────────────────────────

PRUEBA(http_008_a_011_la_forma_de_una_cabecera) {
    COMPRUEBA(estado("GET / HTTP/1.1\r\nHost : x\r\n\r\n") == 400, "HTTP-008");
    COMPRUEBA(estado("GET / HTTP/1.1\r\nHost: x\r\nX-A: uno\r\n dos\r\n\r\n") == 400, "HTTP-009");
    COMPRUEBA(estado("GET / HTTP/1.1\r\nHost: x\r\nX@raro: v\r\n\r\n") == 400, "HTTP-010");
    COMPRUEBA(estado(std::string{"GET / HTTP/1.1\r\nHost: x\r\nX-A: a\0b\r\n\r\n", 36}) == 400,
              "HTTP-011");
}

PRUEBA(http_012_el_blanco_alrededor_del_valor_no_es_el_valor) {
    const auto p = parsea("GET / HTTP/1.1\r\nHost:    x   \r\nX-A: \t v \t\r\n\r\n");
    COMPRUEBA(p && p->cabecera("host") == "x", "HTTP-012");
    COMPRUEBA(p && p->cabecera("x-a") == "v", "HTTP-012: tabuladores también");
}

PRUEBA(las_cabeceras_se_buscan_sin_mirar_mayusculas) {
    const auto p = parsea("GET / HTTP/1.1\r\nHost: x\r\nX-Cosa: v\r\n\r\n");
    COMPRUEBA(p && p->cabecera("X-COSA") == "v", "RFC 9110 §5.1");
    COMPRUEBA(p && !p->cabecera("x-no-esta"), "y lo que no está no está");
}

PRUEBA(una_cabecera_repetible_se_une_con_coma) {
    const auto p = parsea("GET / HTTP/1.1\r\nHost: x\r\nAccept: a\r\nAccept: b\r\n\r\n");
    COMPRUEBA(p && p->cabecera("accept") == "a, b", "RFC 9110 §5.3");
}

// ── Host ────────────────────────────────────────────────────────────────────

PRUEBA(http_013_a_015_el_host) {
    COMPRUEBA(estado("GET / HTTP/1.1\r\n\r\n") == 400, "HTTP-013");
    COMPRUEBA(estado("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n") == 400, "HTTP-014");
    COMPRUEBA(estado("GET / HTTP/1.0\r\n\r\n") == 200, "HTTP-015");
    COMPRUEBA(estado("GET / HTTP/1.1\r\nHost: a\r\nHost: a\r\n\r\n") == 400,
              "HTTP-014: repetido es repetido aunque diga lo mismo");
}

// ── Longitud del cuerpo ─────────────────────────────────────────────────────
//
// Aquí vive el contrabando de peticiones, y por eso los cuatro son rechazos y no correcciones.

PRUEBA(http_016_a_019_lo_que_mide_el_cuerpo) {
    COMPRUEBA(estado("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n"
                     "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n") == 400, "HTTP-016");
    COMPRUEBA(estado("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n"
                     "Content-Length: 2\r\n\r\na") == 400, "HTTP-017");
    COMPRUEBA(estado("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n") == 400,
              "HTTP-018");
    COMPRUEBA(estado("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: ocho\r\n\r\n") == 400,
              "HTTP-019");

    const auto bueno = parsea("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\nhola");
    COMPRUEBA(bueno && bueno->cuerpo == "hola", "y el que cuadra pasa");
    COMPRUEBA(estado("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n"
                     "Content-Length: 1\r\n\r\na") == 200,
              "HTTP-017: dos veces el mismo valor no es discrepancia");
}

// ── Codificación por trozos ─────────────────────────────────────────────────

PRUEBA(http_020_a_022_los_trozos) {
    COMPRUEBA(estado("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked, gzip\r\n\r\n"
                     "0\r\n\r\n") == 400, "HTTP-020");
    COMPRUEBA(estado("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n")
              == 400, "HTTP-021");

    const auto p = parsea("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                          "5\r\nhola \r\n4\r\nmund\r\n0\r\n\r\n");
    COMPRUEBA(p && p->cuerpo == "hola mund", "HTTP-022");
    COMPRUEBA(estado("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: gzip, chunked\r\n\r\n"
                     "0\r\n\r\n") == 200, "HTTP-020: en la última posición sí vale");
}

PRUEBA(un_cr_suelto_dentro_de_la_linea_no_se_tolera) {
    // El RFC 9112 §2.2 pide CRLF. Aceptar LF suelto es la tolerancia habitual; un CR en medio no,
    // porque es la vía del contrabando cuando hay un intermediario que sí lo parte.
    COMPRUEBA(estado("GET / HTTP/1.1\nHost: x\n\n") == 200, "LF suelto se tolera");
    COMPRUEBA(estado("GET / HTTP/1.1\r\nHost: x\rX-A: b\r\n\r\n") == 400, "CR en medio, no");
}

}  // namespace

PRUEBAS_MAIN
