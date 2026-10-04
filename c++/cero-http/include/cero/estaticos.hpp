#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <utility>

#include "cero/respuesta.hpp"

namespace cero {

// Servir un directorio es la primera forma de abrir un agujero: `GET /../../etc/passwd`. El camino
// se resuelve y se comprueba que sigue dentro de la raíz **después** de resolverlo, que es lo único
// que ataja los enlaces simbólicos además del `..`.
class Estaticos {
public:
    static std::optional<Estaticos> en(std::string_view raiz);

    // Si el camino no existe se sirve esto. Para una aplicación de una sola página, `index.html`.
    Estaticos&& con_respaldo(std::string_view archivo) &&;

    Respuesta servir(std::string_view camino) const;

private:
    explicit Estaticos(std::filesystem::path raiz) : raiz_(std::move(raiz)) {}
    Respuesta respaldar() const;

    std::filesystem::path raiz_;
    std::optional<std::string> respaldo_;
};

std::string_view tipo_de(const std::filesystem::path& ruta);

}  // namespace cero
