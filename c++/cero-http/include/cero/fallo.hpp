#pragma once

#include <optional>
#include <string>
#include <utility>

#include "cero/json.hpp"

namespace cero {

struct Fallo {
    unsigned estado;
    std::string mensaje;
    std::optional<Json> detalle;

    Fallo(unsigned e, std::string m, std::optional<Json> d = std::nullopt)
        : estado(e), mensaje(std::move(m)), detalle(std::move(d)) {}

    static Fallo interno(std::string mensaje) { return {500, std::move(mensaje)}; }

    bool contable() const { return estado < 500; }
};

}
