#include "cero/validacion.hpp"

#include <algorithm>
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

size_t caracteres(string_view texto) {
    return ranges::count_if(texto, [](unsigned char c) { return (c & 0xC0) != 0x80; });
}

struct Juez {
    const Json* valor;

    optional<string> operator()(Obligatorio) const {
        if (valor == nullptr || valor->cadena() == "") return "es obligatorio";
        return nullopt;
    }

    optional<string> operator()(Texto regla) const {
        if (valor == nullptr) return nullopt;
        const auto texto = valor->cadena();
        if (!texto) return "tiene que ser texto";
        if (caracteres(*texto) < regla.minimo) return formato("no llega a {} caracteres", regla.minimo);
        if (caracteres(*texto) > regla.maximo) return formato("pasa de {} caracteres", regla.maximo);
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
