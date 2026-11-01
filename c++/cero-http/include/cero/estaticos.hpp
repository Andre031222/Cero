#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <utility>

#include "cero/respuesta.hpp"

namespace cero {

class Estaticos {
public:
    static std::optional<Estaticos> en(std::string_view raiz);

    Estaticos&& con_respaldo(std::string_view archivo) &&;

    Respuesta servir(std::string_view camino) const;

private:
    explicit Estaticos(std::filesystem::path raiz) : raiz_(std::move(raiz)) {}
    Respuesta respaldar() const;

    std::filesystem::path raiz_;
    std::optional<std::string> respaldo_;
};

std::string_view tipo_de(const std::filesystem::path& ruta);

}
