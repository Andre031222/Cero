// La capa de tramas de HTTP/2: una prueba por requisito de `spec/http2.md` que esta capa verifica.
//
// Casi todos son rechazos. La asimetría no es casual: un servidor HTTP/2 permisivo es un servidor
// que desacuerda con el proxy que tiene delante, y ahí empieza el contrabando.

#include <cstdint>
#include <string>
#include <string_view>

#include "cero/http2/trama.hpp"
#include "prueba.hpp"

namespace {

using namespace cero::http2;

class DesdeTexto final : public Fuente {
public:
    explicit DesdeTexto(std::string_view t) : t_(t) {}
    std::size_t leer(char* destino, std::size_t cuantos) override {
        const auto cabe = std::min(cuantos, t_.size() - i_);
        t_.copy(destino, cabe, i_);
        i_ += cabe;
        return cabe;
    }

private:
    std::string t_;
    std::size_t i_ = 0;
};

Trama t(std::uint8_t tipo, std::uint8_t banderas, std::uint32_t flujo, std::string carga) {
    return {tipo, banderas, flujo, std::move(carga)};
}

std::optional<Error> rechaza(const Trama& trama) {
    const auto r = trama.comprobar();
    return r ? std::nullopt : std::optional{r.error().codigo};
}

PRUEBA(h2_001_el_preambulo_es_el_preambulo) {
    COMPRUEBA(kPreambulo == "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", "H2-001");
}

PRUEBA(h2_002_y_003_los_settings) {
    COMPRUEBA(rechaza(t(kSettings, 0, 1, "")) == Error::Protocolo, "H2-002");
    COMPRUEBA(rechaza(t(kSettings, 0, 0, std::string(5, '\0'))) == Error::TamanoDeTrama, "H2-003");
    COMPRUEBA(!rechaza(t(kSettings, 0, 0, std::string(12, '\0'))).has_value(), "doce sí vale");
    COMPRUEBA(rechaza(t(kSettings, kReconoce, 0, "x")) == Error::TamanoDeTrama, "ACK con carga");
    COMPRUEBA(!rechaza(t(kSettings, kReconoce, 0, "")).has_value(), "un ACK vacío sí");
}

PRUEBA(h2_004_a_006_los_flujos_que_no_valen) {
    COMPRUEBA(rechaza(t(kData, 0, 0, "x")) == Error::Protocolo, "H2-004");
    COMPRUEBA(rechaza(t(kHeaders, 0, 0, "x")) == Error::Protocolo, "H2-005");
    // Los pares son del servidor: un cliente que abre uno está hablando de un flujo que no es suyo.
    COMPRUEBA(rechaza(t(kHeaders, 0, 2, "x")) == Error::Protocolo, "H2-006");
    COMPRUEBA(!rechaza(t(kHeaders, 0, 3, "x")).has_value(), "y los impares sí");
}

PRUEBA(h2_009_un_cliente_no_promete_nada) {
    COMPRUEBA(rechaza(t(kPushPromise, 0, 1, std::string(4, '\0'))) == Error::Protocolo, "H2-009");
}

PRUEBA(h2_010_y_011_el_ping) {
    COMPRUEBA(rechaza(t(kPing, 0, 1, std::string(8, '\0'))) == Error::Protocolo, "H2-011");
    COMPRUEBA(rechaza(t(kPing, 0, 0, std::string(7, '\0'))) == Error::TamanoDeTrama, "H2-010");
    COMPRUEBA(!rechaza(t(kPing, 0, 0, std::string(8, '\0'))).has_value(), "ocho exactos");
}

PRUEBA(h2_012_y_013_el_window_update) {
    COMPRUEBA(rechaza(t(kWindowUpdate, 0, 0, std::string(3, '\0'))) == Error::TamanoDeTrama,
              "H2-012");
    COMPRUEBA(rechaza(t(kWindowUpdate, 0, 0, std::string(4, '\0'))) == Error::Protocolo,
              "H2-013: incremento cero sobre la conexión");
    COMPRUEBA(!rechaza(Trama::ventana(0, 1)).has_value(), "uno sí vale");
}

PRUEBA(las_demas_formas_que_el_rfc_rechaza) {
    COMPRUEBA(rechaza(t(kRstStream, 0, 0, std::string(4, '\0'))) == Error::Protocolo,
              "RST_STREAM sobre el flujo 0");
    COMPRUEBA(rechaza(t(kRstStream, 0, 1, std::string(3, '\0'))) == Error::TamanoDeTrama,
              "RST_STREAM que no mide cuatro");
    COMPRUEBA(rechaza(t(kPriority, 0, 1, std::string(4, '\0'))) == Error::TamanoDeTrama,
              "PRIORITY que no mide cinco");
    COMPRUEBA(rechaza(t(kGoaway, 0, 1, std::string(8, '\0'))) == Error::Protocolo,
              "GOAWAY sobre un flujo");
}

PRUEBA(h2_014_una_trama_mayor_que_el_maximo) {
    // La cabecera anuncia 20 000 y el máximo son 16 384: se rechaza **antes** de leer la carga,
    // porque si no, anunciar 16 MB bastaría para que el servidor los reservara.
    std::string cabecera;
    cabecera += '\x00';
    cabecera += '\x4e';
    cabecera += '\x20';
    cabecera += static_cast<char>(kData);
    cabecera += '\x00';
    cabecera += std::string(4, '\0');
    cabecera.back() = 1;

    DesdeTexto origen{cabecera};
    const auto r = leer_trama(origen, 16'384);
    COMPRUEBA(!r.has_value() && r.error().codigo == Error::TamanoDeTrama, "H2-014");
}

PRUEBA(una_trama_se_escribe_y_se_vuelve_a_leer_igual) {
    const auto original = t(kData, kFinFlujo, 7, "hola mundo");
    DesdeTexto origen{original.octetos()};
    const auto vuelta = leer_trama(origen, 16'384);
    COMPRUEBA(vuelta.has_value(), "se lee");
    COMPRUEBA(vuelta && *vuelta == original, "y da lo mismo");
    COMPRUEBA(vuelta && vuelta->fin_flujo(), "con sus banderas");
}

PRUEBA(el_bit_reservado_del_identificador_se_ignora_no_se_rechaza) {
    auto octetos = t(kData, 0, 1, "x").octetos();
    octetos[5] = static_cast<char>(static_cast<unsigned char>(octetos[5]) | 0x80);
    DesdeTexto origen{octetos};
    const auto r = leer_trama(origen, 16'384);
    COMPRUEBA(r.has_value() && r->flujo == 1, "§4.1: se ignora");
}

PRUEBA(h2_027_los_ajustes_del_servidor_anuncian_push_deshabilitado) {
    const auto primera = Ajustes{}.trama();
    COMPRUEBA(primera.tipo == kSettings && primera.flujo == 0, "H2-027");
    COMPRUEBA(primera.banderas == 0, "no es un ACK");
    bool anuncia = false;
    for (std::size_t i = 0; i + 6 <= primera.carga.size(); i += 6) {
        if (primera.carga[i] == 0 && primera.carga[i + 1] == 2) {
            anuncia = entero32(primera.carga, i + 2) == 0;
        }
    }
    COMPRUEBA(anuncia, "H2-027: push deshabilitado");
}

PRUEBA(los_ajustes_del_cliente_se_aplican_y_los_imposibles_se_rechazan) {
    Ajustes par = Ajustes::del_rfc();
    // MAX_FRAME_SIZE = 0x00ffffff, dentro de rango.
    const std::string bueno{"\x00\x05\x00\xff\xff\xff", 6};
    COMPRUEBA(par.aplicar(bueno).has_value() && par.max_trama == 0x00ff'ffff, "§6.5.2");

    Ajustes otro = Ajustes::del_rfc();
    const std::string pequeno{"\x00\x05\x00\x00\x00\x01", 6};
    COMPRUEBA(!otro.aplicar(pequeno).has_value(), "MAX_FRAME_SIZE fuera de rango");

    Ajustes mas = Ajustes::del_rfc();
    const std::string imposible{"\x00\x04\xff\xff\xff\xff", 6};
    const auto r = mas.aplicar(imposible);
    COMPRUEBA(!r.has_value() && r.error().codigo == Error::ControlDeFlujo, "ventana imposible");

    Ajustes push = Ajustes::del_rfc();
    const std::string malo{"\x00\x02\x00\x00\x00\x02", 6};
    COMPRUEBA(!push.aplicar(malo).has_value(), "ENABLE_PUSH que no es 0 ni 1");
}

PRUEBA(un_ajuste_desconocido_se_ignora_no_se_rechaza) {
    // Es lo que permite extender el protocolo sin romper a quien no lo conoce.
    Ajustes par = Ajustes::del_rfc();
    const std::string raro{"\x00\x63\x00\x00\x00\x07", 6};
    COMPRUEBA(par.aplicar(raro).has_value(), "§6.5.2");
}

PRUEBA(la_ventana_inicial_repetida_en_una_trama_acumula_su_delta) {
    // Quedarse con el último delta en vez de la suma da un total que no es ni el primero ni el
    // segundo, y entonces el cliente y el servidor cuentan distinto.
    Ajustes par = Ajustes::del_rfc();
    const std::string dos_veces{"\x00\x04\x00\x00\x00\x64\x00\x04\x00\x00\x00\xc8", 12};
    const auto delta = par.aplicar(dos_veces);
    COMPRUEBA(delta.has_value(), "§6.5.3");
    COMPRUEBA(delta && *delta == 200 - 65'535, "la suma, no el último");
    COMPRUEBA(par.ventana_inicial == 200, "y queda el último valor");
}

PRUEBA(los_ajustes_que_se_le_suponen_al_cliente_son_los_del_rfc) {
    // Suponer aquí los nuestros sería mandarle tramas de un tamaño que no ha aceptado.
    const auto par = Ajustes::del_rfc();
    COMPRUEBA(par.max_trama == 16'384, "§6.5.2");
    COMPRUEBA(par.ventana_inicial == 65'535, "§6.5.2");
    COMPRUEBA(Ajustes{}.max_flujos == 128, "y los nuestros son otros");
}

}  // namespace

PRUEBAS_MAIN
