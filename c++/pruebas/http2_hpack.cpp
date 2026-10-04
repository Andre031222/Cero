// HPACK contra el apéndice C del RFC 7541, más los requisitos de `spec/http2.md` que le tocan.
//
// Los vectores del apéndice son la única comprobación que no se puede escribir «de acuerdo con lo
// que hace el código»: los octetos y el estado de la tabla vienen dados, y la tabla dinámica
// después de cada paso está en el RFC. Un decodificador que acierte el resultado pero deje la
// tabla distinta pasaría cualquier prueba propia y fallaría en la petición siguiente.

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include "cero/http2/hpack.hpp"
#include "cero/http2/hpack_tablas.hpp"
#include "prueba.hpp"

namespace {

using namespace cero::http2;

std::string hex(std::string_view s) {
    std::string limpio;
    for (char c : s) {
        if (std::isxdigit(static_cast<unsigned char>(c)) != 0) limpio += c;
    }
    std::string salida;
    for (std::size_t i = 0; i + 1 < limpio.size(); i += 2) {
        salida += static_cast<char>(std::stoi(limpio.substr(i, 2), nullptr, 16));
    }
    return salida;
}

Decodificador d() { return Decodificador{4096, 64 * 1024}; }

std::vector<Cabecera> pares(std::vector<std::pair<std::string_view, std::string_view>> v) {
    std::vector<Cabecera> salida;
    for (const auto& [n, x] : v) salida.emplace_back(std::string{n}, std::string{x});
    return salida;
}

// ── Las tablas ──────────────────────────────────────────────────────────────

PRUEBA(el_codigo_de_huffman_es_un_codigo_prefijo_completo) {
    // La suma de Kraft de un código prefijo completo vale exactamente 1. Es lo que demuestra que
    // no falta ni sobra un símbolo en las 257 filas; una tabla mal copiada no revienta, decodifica
    // mal y en silencio.
    double suma = 0;
    for (auto l : kLargo) suma += std::pow(2.0, -static_cast<int>(l));
    COMPRUEBA(std::fabs(suma - 1.0) < 1e-12, "suma de Kraft");
    COMPRUEBA(kCodigo.size() == 257 && kLargo.size() == 257, "257 símbolos");
    COMPRUEBA(kEstatica.size() == 61, "61 entradas estáticas");
    COMPRUEBA(kEstatica[0].first == ":authority", "la primera");
    COMPRUEBA(kEstatica[60].first == "www-authenticate" && kEstatica[60].second.empty(),
              "y la última");
}

// ── Apéndice C.2: las cuatro formas de representar una cabecera ─────────────

PRUEBA(c21_literal_con_nombre_literal_e_indexado) {
    auto dec = d();
    const auto salida = dec.decodificar(
        hex("400a 6375 7374 6f6d 2d6b 6579 0d63 7573 746f 6d2d 6865 6164 6572"));
    COMPRUEBA(salida.has_value() && *salida == pares({{"custom-key", "custom-header"}}), "C.2.1");
    COMPRUEBA(dec.tabla().tamano() == 1, "C.2.1: entra en la tabla");
    COMPRUEBA(dec.tabla().ocupado() == 55, "C.2.1: y ocupa lo que dice el RFC");
}

PRUEBA(c22_literal_sin_indexar) {
    auto dec = d();
    const auto salida = dec.decodificar(hex("040c 2f73 616d 706c 652f 7061 7468"));
    COMPRUEBA(salida.has_value() && *salida == pares({{":path", "/sample/path"}}), "C.2.2");
    COMPRUEBA(dec.tabla().tamano() == 0, "sin indexar no toca la tabla");
}

PRUEBA(c23_literal_que_nunca_se_indexa) {
    auto dec = d();
    const auto salida = dec.decodificar(hex("1008 7061 7373 776f 7264 0673 6563 7265 74"));
    COMPRUEBA(salida.has_value() && *salida == pares({{"password", "secret"}}), "C.2.3");
    COMPRUEBA(dec.tabla().tamano() == 0, "C.2.3");
}

PRUEBA(c24_indexado) {
    auto dec = d();
    const auto salida = dec.decodificar(hex("82"));
    COMPRUEBA(salida.has_value() && *salida == pares({{":method", "GET"}}), "C.2.4");
}

// ── Apéndice C.3: tres peticiones seguidas, sin Huffman ─────────────────────
//
// Es el caso que de verdad ejercita la tabla: la segunda petición se apoya en lo que dejó la
// primera, y la tercera en las dos. Decodificarlas por separado no prueba nada.

PRUEBA(c3_tres_peticiones_encadenadas_sin_huffman) {
    auto dec = d();

    const auto uno = dec.decodificar(hex("8286 8441 0f77 7777 2e65 7861 6d70 6c65 2e63 6f6d"));
    COMPRUEBA(uno && *uno == pares({{":method", "GET"},
                                    {":scheme", "http"},
                                    {":path", "/"},
                                    {":authority", "www.example.com"}}),
              "C.3.1");
    COMPRUEBA(dec.tabla().ocupado() == 57, "C.3.1");

    const auto dos = dec.decodificar(hex("8286 84be 5808 6e6f 2d63 6163 6865"));
    COMPRUEBA(dos && dos->size() == 5 && (*dos)[4] == Cabecera{"cache-control", "no-cache"},
              "C.3.2");
    COMPRUEBA(dec.tabla().ocupado() == 110, "C.3.2");

    const auto tres = dec.decodificar(
        hex("8287 85bf 400a 6375 7374 6f6d 2d6b 6579 0c63 7573 746f 6d2d 7661 6c75 65"));
    COMPRUEBA(tres && *tres == pares({{":method", "GET"},
                                      {":scheme", "https"},
                                      {":path", "/index.html"},
                                      {":authority", "www.example.com"},
                                      {"custom-key", "custom-value"}}),
              "C.3.3");
    COMPRUEBA(dec.tabla().ocupado() == 164 && dec.tabla().tamano() == 3, "C.3.3");
}

// ── Apéndice C.4: las mismas tres, con Huffman ──────────────────────────────

PRUEBA(c4_tres_peticiones_encadenadas_con_huffman) {
    auto dec = d();

    const auto uno = dec.decodificar(hex("8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff"));
    COMPRUEBA(uno && (*uno)[3] == Cabecera{":authority", "www.example.com"}, "C.4.1");
    COMPRUEBA(dec.tabla().ocupado() == 57, "C.4.1");

    const auto dos = dec.decodificar(hex("8286 84be 5886 a8eb 1064 9cbf"));
    COMPRUEBA(dos && (*dos)[4] == Cabecera{"cache-control", "no-cache"}, "C.4.2");
    COMPRUEBA(dec.tabla().ocupado() == 110, "C.4.2");

    const auto tres =
        dec.decodificar(hex("8287 85bf 4088 25a8 49e9 5ba9 7d7f 8925 a849 e95b b8e8 b4bf"));
    COMPRUEBA(tres && (*tres)[4] == Cabecera{"custom-key", "custom-value"}, "C.4.3");
    COMPRUEBA(dec.tabla().ocupado() == 164, "C.4.3");
}

// ── Apéndice C.5: respuestas con una tabla de 256 octetos, que obliga a desalojar ──

PRUEBA(c5_respuestas_con_tabla_pequena_desalojan) {
    Decodificador dec{256, 64 * 1024};

    const auto uno = dec.decodificar(hex(
        "4803 3330 3258 0770 7269 7661 7465 611d 4d6f 6e2c 2032 3120 4f63 7420 3230 3133 2032 303a "
        "3133 3a32 3120 474d 546e 1768 7474 7073 3a2f 2f77 7777 2e65 7861 6d70 6c65 2e63 6f6d"));
    COMPRUEBA(uno && (*uno)[0] == Cabecera{":status", "302"}, "C.5.1");
    COMPRUEBA(dec.tabla().ocupado() == 222, "C.5.1");

    const auto dos = dec.decodificar(hex("4803 3330 37c1 c0bf"));
    COMPRUEBA(dos && (*dos)[0] == Cabecera{":status", "307"}, "C.5.2");
    COMPRUEBA(dec.tabla().ocupado() == 222, "C.5.2: entra una y sale otra");

    const auto tres = dec.decodificar(hex(
        "88c1 611d 4d6f 6e2c 2032 3120 4f63 7420 3230 3133 2032 303a 3133 3a32 3220 474d 54c0 5a04 "
        "677a 6970 7738 666f 6f3d 4153 444a 4b48 514b 425a 584f 5157 454f 5049 5541 5851 5745 4f49 "
        "553b 206d 6178 2d61 6765 3d33 3630 303b 2076 6572 7369 6f6e 3d31"));
    COMPRUEBA(tres && (*tres)[0] == Cabecera{":status", "200"}, "C.5.3");
    COMPRUEBA(dec.tabla().ocupado() == 215, "C.5.3");
    COMPRUEBA(dec.tabla().tamano() == 3, "la tabla de 256 no da para más");
}

// ── Apéndice C.6: las mismas respuestas, con Huffman ────────────────────────

PRUEBA(c6_respuestas_con_huffman_y_desalojo) {
    Decodificador dec{256, 64 * 1024};

    COMPRUEBA(dec.decodificar(hex(
                     "4882 6402 5885 aec3 771a 4b61 96d0 7abe 9410 54d4 44a8 2005 9504 0b81 66e0 "
                     "82a6 2d1b ff6e 919d 29ad 1718 63c7 8f0b 97c8 e9ae 82ae 43d3"))
                  .has_value(),
              "C.6.1");
    COMPRUEBA(dec.tabla().ocupado() == 222, "C.6.1");

    COMPRUEBA(dec.decodificar(hex("4883 640e ffc1 c0bf")).has_value(), "C.6.2");
    COMPRUEBA(dec.tabla().ocupado() == 222, "C.6.2");

    const auto tres = dec.decodificar(hex(
        "88c1 6196 d07a be94 1054 d444 a820 0595 040b 8166 e084 a62d 1bff c05a 839b d9ab 77ad 94e7 "
        "821d d7f2 e6c7 b335 dfdf cd5b 3960 d5af 2708 7f36 72c1 ab27 0fb5 291f 9587 3160 65c0 03ed "
        "4ee5 b106 3d50 07"));
    COMPRUEBA(tres && (*tres)[0] == Cabecera{":status", "200"}, "C.6.3");
    COMPRUEBA(dec.tabla().ocupado() == 215, "C.6.3");
}

// ── Los requisitos del contrato ─────────────────────────────────────────────

PRUEBA(h2_015_indice_fuera_de_la_tabla) {
    // Con la dinámica vacía, 62 ya no existe.
    COMPRUEBA(!d().decodificar("\xbe").has_value(), "H2-015");
    COMPRUEBA(!d().decodificar("\x80").has_value(), "H2-015: el índice 0 tampoco vale");
}

PRUEBA(h2_016_eos_dentro_de_una_cadena) {
    // EOS es relleno, nunca un símbolo: treinta bits de unos son EOS entero.
    const std::string bloque{"\x00\x00\x84\xff\xff\xff\xff", 7};
    COMPRUEBA(!d().decodificar(bloque).has_value(), "H2-016");
}

PRUEBA(h2_039_la_lista_que_se_expande_al_descomprimirse) {
    // El tope se mide en lo que **sale**, no en lo que entra. Una entrada grande en la tabla y
    // cien referencias de un octeto caben en tres kilobytes de cable y no en el tope de salida.
    Decodificador dec{4096, 2048};
    std::string bloque;
    bloque += '\x40';  // literal indexado, nombre nuevo
    bloque += '\x03';
    bloque += "big";
    bloque += '\x7f';  // 1000 con prefijo de siete bits: 1000 - 127 = 873
    bloque += '\xe9';
    bloque += '\x06';
    bloque += std::string(1000, 'a');
    bloque += std::string(100, static_cast<char>(0x80 | 62));

    const auto r = dec.decodificar(bloque);
    COMPRUEBA(!r.has_value() && r.error().codigo == Error::Compresion, "H2-039");
}

PRUEBA(las_actualizaciones_de_tabla_tienen_su_sitio_y_su_tope) {
    Decodificador vaciar{4096, 64 * 1024};
    COMPRUEBA(vaciar.decodificar(std::string{"\x20\x82", 2}).has_value(),
              "vaciar y luego indexar sí vale");

    Decodificador grande{256, 64 * 1024};
    COMPRUEBA(!grande.decodificar(std::string{"\x3f\xe1\x1f", 3}).has_value(), "§6.3: 8192 > 256");

    Decodificador tarde{4096, 64 * 1024};
    COMPRUEBA(!tarde.decodificar(std::string{"\x82\x20", 2}).has_value(),
              "§4.2: la actualización va al principio del bloque");
}

PRUEBA(reducir_la_tabla_desaloja_en_el_acto) {
    // Si esperara a tener que meter algo, la otra punta y esta dejarían de contar lo mismo y los
    // índices se correrían.
    Decodificador dec{4096, 64 * 1024};
    COMPRUEBA(dec.decodificar(hex("8286 8441 0f77 7777 2e65 7861 6d70 6c65 2e63 6f6d")).has_value(),
              "se decodifica");
    COMPRUEBA(dec.tabla().tamano() == 1, "hay una entrada");
    COMPRUEBA(dec.tabla().redimensionar(0).has_value(), "se reduce a cero");
    COMPRUEBA(dec.tabla().tamano() == 0 && dec.tabla().ocupado() == 0, "y se vació");
}

PRUEBA(un_entero_sin_fin_no_cuelga) {
    // Un entero que no termina nunca es una tira de `0xff`. Sin tope, el bucle tampoco termina.
    std::string bloque{"\x7f"};
    bloque += std::string(20, '\xff');
    COMPRUEBA(!d().decodificar(bloque).has_value(), "§5.1");
}

PRUEBA(una_cadena_cortada_no_lee_de_mas) {
    COMPRUEBA(!d().decodificar(std::string{"\x00\x0a" "hi", 4}).has_value(), "§5.2");
}

// ── Codificar ───────────────────────────────────────────────────────────────

PRUEBA(lo_codificado_se_decodifica_igual) {
    // Cubre los dos caminos a la vez, incluido el relleno de Huffman, que es donde se equivoca uno.
    const auto cabeceras = pares({
        {":status", "200"},
        {"content-type", "text/html; charset=utf-8"},
        {"content-length", "1234"},
        {"x-propia", "ñandú y un valor largo que comprime bien porque se repite y se repite"},
    });
    const auto bloque = codificar(cabeceras);
    const auto vuelta = d().decodificar(bloque);
    COMPRUEBA(vuelta.has_value() && *vuelta == cabeceras, "ida y vuelta");
}

PRUEBA(una_entrada_de_la_estatica_ocupa_un_octeto) {
    // Si deja de ser así, la respuesta más común del servidor engorda en cada petición.
    COMPRUEBA(codificar(pares({{":status", "200"}})) == std::string{"\x88"}, "§2.3.1");
}

PRUEBA(los_nombres_salen_en_minusculas) {
    // Una mayúscula en un nombre de campo es un mensaje malformado en HTTP/2 (§8.2.1), así que no
    // puede salir de aquí aunque quien llame la escriba.
    const auto bloque = codificar(pares({{"Content-Type", "text/plain"}}));
    const auto leidas = d().decodificar(bloque);
    COMPRUEBA(leidas && (*leidas)[0].first == "content-type", "H2-033");
}

PRUEBA(codificar_no_mueve_la_tabla_de_quien_lee) {
    // Se emite sin indexar a propósito, y eso tiene que seguir siendo verdad porque es lo que hace
    // la salida reproducible.
    const auto bloque = codificar(pares({{"x-una", "cosa"}}));
    auto dec = d();
    COMPRUEBA(dec.decodificar(bloque).has_value(), "se lee");
    COMPRUEBA(dec.tabla().tamano() == 0, "y la tabla sigue vacía");
}

}  // namespace

PRUEBAS_MAIN
