#pragma once

#include <cstddef>
#include <string>
#include <utility>
#include <variant>
#include <vector>

#include "cero/json.hpp"

namespace cero {

struct Obligatorio {};

struct Texto {
    std::size_t minimo;
    std::size_t maximo;
};

struct Entero {
    long long minimo;
    long long maximo;
};

using Regla = std::variant<Obligatorio, Texto, Entero>;
using Reglas = std::vector<std::pair<std::string, Regla>>;

Json validar(Json cuerpo, const Reglas& reglas);

}
