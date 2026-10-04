#include "cero/http2/trama.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <string>
#include <string_view>
#include <utility>

namespace cero::http2 {
namespace {

using Fallo = std::unexpected<FalloConexion>;

Fallo mal(Error codigo, std::string_view porque) { return Fallo{FalloConexion{codigo, porque}}; }

std::string cuatro(std::uint32_t v) {
    std::string s;
    for (int desplaza : {24, 16, 8, 0}) s += static_cast<char>((v >> desplaza) & 0xff);
    return s;
}

}  // namespace

std::uint32_t entero32(std::string_view donde, std::size_t desde) {
    if (desde + 4 > donde.size()) return 0;
    const auto b = [&](std::size_t i) {
        return static_cast<std::uint32_t>(static_cast<unsigned char>(donde[desde + i]));
    };
    return (b(0) << 24) | (b(1) << 16) | (b(2) << 8) | b(3);
}

std::string Trama::octetos() const {
    const auto n = carga.size();
    std::string salida;
    salida.reserve(9 + n);
    for (int desplaza : {16, 8, 0}) salida += static_cast<char>((n >> desplaza) & 0xff);
    salida += static_cast<char>(tipo);
    salida += static_cast<char>(banderas);
    salida += cuatro(flujo);
    salida += carga;
    return salida;
}

Resultado<void> Trama::comprobar() const {
    switch (tipo) {
        case kData:
            // H2-004.
            if (flujo == 0) return mal(Error::Protocolo, "DATA sobre el flujo 0");
            break;
        case kHeaders:
            // H2-005 y H2-006.
            if (flujo == 0) return mal(Error::Protocolo, "HEADERS sobre el flujo 0");
            if (flujo % 2 == 0) {
                return mal(Error::Protocolo, "HEADERS sobre un flujo par: los pares son del servidor");
            }
            break;
        case kSettings:
            // H2-002 y H2-003.
            if (flujo != 0) return mal(Error::Protocolo, "SETTINGS sobre un flujo");
            if ((banderas & kReconoce) != 0 && !carga.empty()) {
                return mal(Error::TamanoDeTrama, "SETTINGS con ACK y carga");
            }
            if (carga.size() % 6 != 0) {
                return mal(Error::TamanoDeTrama, "SETTINGS no múltiplo de seis");
            }
            break;
        // H2-009: un cliente no promete nada.
        case kPushPromise: return mal(Error::Protocolo, "PUSH_PROMISE de un cliente");
        case kPing:
            // H2-010 y H2-011.
            if (flujo != 0) return mal(Error::Protocolo, "PING sobre un flujo");
            if (carga.size() != 8) return mal(Error::TamanoDeTrama, "PING que no mide ocho");
            break;
        case kWindowUpdate:
            // H2-012 y H2-013.
            if (carga.size() != 4) {
                return mal(Error::TamanoDeTrama, "WINDOW_UPDATE que no mide cuatro");
            }
            if (flujo == 0 && (entero32(carga, 0) & 0x7fff'ffff) == 0) {
                return mal(Error::Protocolo, "incremento de ventana cero sobre la conexión");
            }
            break;
        case kRstStream:
            if (flujo == 0) return mal(Error::Protocolo, "RST_STREAM sobre el flujo 0");
            if (carga.size() != 4) return mal(Error::TamanoDeTrama, "RST_STREAM que no mide cuatro");
            break;
        case kPriority:
            if (carga.size() != 5) return mal(Error::TamanoDeTrama, "PRIORITY que no mide cinco");
            break;
        case kGoaway:
            if (flujo != 0) return mal(Error::Protocolo, "GOAWAY sobre un flujo");
            break;
        default: break;
    }
    return {};
}

Trama Trama::ping_ack(std::string_view carga) { return {kPing, kReconoce, 0, std::string{carga}}; }

Trama Trama::settings_ack() { return {kSettings, kReconoce, 0, {}}; }

Trama Trama::rst(std::uint32_t flujo, Error codigo) {
    return {kRstStream, 0, flujo, cuatro(static_cast<std::uint32_t>(codigo))};
}

Trama Trama::goaway(std::uint32_t ultimo, Error codigo) {
    return {kGoaway, 0, 0, cuatro(ultimo) + cuatro(static_cast<std::uint32_t>(codigo))};
}

Trama Trama::ventana(std::uint32_t flujo, std::uint32_t cuanto) {
    return {kWindowUpdate, 0, flujo, cuatro(cuanto)};
}

Resultado<Trama> leer_trama(Fuente& origen, std::uint32_t max) {
    char cabecera[9];
    if (origen.leer(cabecera, 9) != 9) return mal(Error::Ninguno, "la conexión se cerró");

    const auto b = [&](std::size_t i) {
        return static_cast<std::uint32_t>(static_cast<unsigned char>(cabecera[i]));
    };
    const std::uint32_t largo = (b(0) << 16) | (b(1) << 8) | b(2);
    if (largo > max) return mal(Error::TamanoDeTrama, "trama mayor que el máximo negociado");

    Trama t;
    t.tipo = static_cast<std::uint8_t>(b(3));
    t.banderas = static_cast<std::uint8_t>(b(4));
    // El bit más alto del identificador está reservado y se ignora, no se rechaza.
    t.flujo = ((b(5) & 0x7f) << 24) | (b(6) << 16) | (b(7) << 8) | b(8);
    t.carga.resize(largo);
    if (largo > 0 && origen.leer(t.carga.data(), largo) != largo) {
        return mal(Error::Ninguno, "carga incompleta");
    }
    return t;
}

Ajustes Ajustes::del_rfc() {
    return {.max_flujos = 0xffff'ffff,
            .ventana_inicial = 65'535,
            .max_trama = 16'384,
            .max_cabeceras = 0xffff'ffff};
}

Trama Ajustes::trama() const {
    std::string c;
    const std::pair<std::uint16_t, std::uint32_t> pares[]{
        {0x2, 0},  // push deshabilitado
        {0x3, max_flujos}, {0x4, ventana_inicial}, {0x5, max_trama}, {0x6, max_cabeceras},
    };
    for (const auto& [id, v] : pares) {
        c += static_cast<char>(id >> 8);
        c += static_cast<char>(id & 0xff);
        c += cuatro(v);
    }
    return {kSettings, 0, 0, std::move(c)};
}

Resultado<std::int64_t> Ajustes::aplicar(std::string_view carga) {
    std::int64_t delta = 0;
    for (std::size_t i = 0; i + 6 <= carga.size(); i += 6) {
        const auto id = static_cast<std::uint16_t>(
            (static_cast<unsigned char>(carga[i]) << 8) | static_cast<unsigned char>(carga[i + 1]));
        const auto v = entero32(carga, i + 2);
        switch (id) {
            case 0x2:
                if (v > 1) return mal(Error::Protocolo, "ENABLE_PUSH que no es 0 ni 1");
                break;
            case 0x3: max_flujos = v; break;
            case 0x4:
                if (v > 0x7fff'ffff) return mal(Error::ControlDeFlujo, "ventana inicial imposible");
                // Se acumula: una misma trama puede traer el ajuste dos veces y hay que aplicarlos
                // en orden (§6.5.3). Quedarse con el último delta en vez de la suma da un total
                // que no es ni el primero ni el segundo.
                delta += static_cast<std::int64_t>(v) - static_cast<std::int64_t>(ventana_inicial);
                ventana_inicial = v;
                break;
            case 0x5:
                if (v < 16'384 || v > 16'777'215) {
                    return mal(Error::Protocolo, "MAX_FRAME_SIZE fuera de rango");
                }
                max_trama = v;
                break;
            case 0x6: max_cabeceras = v; break;
            // Un ajuste desconocido se ignora, no se rechaza: es lo que permite extender el
            // protocolo sin romper a quien no lo conoce.
            default: break;
        }
    }
    return delta;
}

}  // namespace cero::http2
