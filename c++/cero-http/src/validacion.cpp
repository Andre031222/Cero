#include "cero/validacion.hpp"

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>

#include "cero/fallo.hpp"
#include "cero/formato.hpp"

using namespace std;

namespace cero {
namespace {

optional<size_t> caracteres(string_view texto) {
    size_t n = 0;
    for (size_t i = 0; i < texto.size(); ++n) {
        const auto c = static_cast<unsigned char>(texto[i]);
        const size_t largo = c < 0x80 ? 1 : c >= 0xC2 && c <= 0xDF ? 2 : c >= 0xE0 && c <= 0xEF ? 3
                           : c >= 0xF0 && c <= 0xF4 ? 4 : 0;
        if (largo == 0 || i + largo > texto.size()) return nullopt;
        unsigned punto = largo == 1 ? c : c & (0x7F >> largo);
        for (size_t k = 1; k < largo; ++k) {
            const auto siguiente = static_cast<unsigned char>(texto[i + k]);
            if ((siguiente & 0xC0) != 0x80) return nullopt;
            punto = (punto << 6) | (siguiente & 0x3F);
        }
        const unsigned minimo = largo == 3 ? 0x800 : largo == 4 ? 0x10000 : 0;
        if (punto < minimo || punto > 0x10FFFF || (punto >= 0xD800 && punto <= 0xDFFF)) return nullopt;
        i += largo;
    }
    return n;
}

bool vacio(const Json& v) {
    if (v == Json{} || v.cadena() == "") return true;
    if (const auto* l = v.lista()) return l->empty();
    if (const auto* o = v.como_objeto()) return o->empty();
    return false;
}

struct Juez {
    const Json* valor;

    optional<string> operator()(Obligatorio) const {
        if (valor == nullptr || vacio(*valor)) return "es obligatorio";
        return nullopt;
    }

    optional<string> operator()(Texto regla) const {
        if (valor == nullptr) return nullopt;
        const auto texto = valor->cadena();
        if (!texto) return "tiene que ser texto";
        const auto n = caracteres(*texto);
        if (!n) return "no es UTF-8 válido";
        if (*n < regla.minimo) return formato("no llega a {} caracteres", regla.minimo);
        if (*n > regla.maximo) return formato("pasa de {} caracteres", regla.maximo);
        return nullopt;
    }

    optional<string> operator()(Entero regla) const {
        if (valor == nullptr) return nullopt;
        const auto n = valor->entero();
        if (!n) return "tiene que ser un número entero";
        if (*n < regla.minimo) return formato("es menor que {}", regla.minimo);
        if (*n > regla.maximo) return formato("es mayor que {}", regla.maximo);
        return nullopt;
    }
};

}

Json validar(Json cuerpo, const Reglas& reglas) {
    Json::Objeto campos;
    for (const auto& [campo, regla] : reglas) {
        if (campos.contains(campo)) continue;
        if (auto motivo = visit(Juez{cuerpo.get(campo)}, regla)) campos.emplace(campo, std::move(*motivo));
    }
    if (campos.empty()) return cuerpo;
    const string mensaje = "el cuerpo no es válido";
    throw Fallo{422, mensaje, Json::objeto({{"error", mensaje}, {"campos", std::move(campos)}})};
}

}
