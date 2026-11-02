#pragma once

#include <optional>
#include <stdexcept>
#include <string>
#include <utility>

#include "cero/json.hpp"

namespace cero {

struct Fallo {
    unsigned estado;
    std::string mensaje;
    std::optional<Json> detalle;

    Fallo(unsigned e, std::string m, std::optional<Json> d = std::nullopt)
        : estado(e), mensaje(std::move(m)), detalle(std::move(d)) {
        if (estado < 400 || estado > 599) {
            throw std::invalid_argument("un Fallo lleva un estado de 400 a 599, no " +
                                        std::to_string(estado));
        }
    }

    static Fallo interno(std::string mensaje) { return {500, std::move(mensaje)}; }

    bool contable() const { return estado < 500; }
};

}
