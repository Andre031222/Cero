#pragma once

#include <expected>
#include <optional>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

#include "cero/tipos.hpp"

namespace cero {

using Captura = PorNombre<std::string>;

class Patron {
public:
    static std::expected<Patron, std::string> nuevo(std::string_view crudo);

    std::optional<Captura> casa(std::string_view camino) const;
    std::size_t literales() const;
    const std::string& crudo() const { return crudo_; }

private:
    explicit Patron(std::string_view crudo) : crudo_(crudo) {}
    std::string crudo_;
};

struct Encontrada {
    std::string nombre;
    Captura variables;
};
struct VerboNoPermitido {
    Textos verbos;
};
struct NoHay {};

using Resolucion = std::variant<Encontrada, VerboNoPermitido, NoHay>;

class Router {
public:
    std::expected<void, std::string> ruta(std::string_view metodo, std::string_view patron,
                                          std::string_view nombre);
    Resolucion resolver(std::string_view metodo, std::string_view camino) const;

    std::optional<std::string> patron_de(std::string_view metodo, std::string_view camino) const;

private:
    struct Ruta {
        std::string metodo;
        Patron patron;
        std::string nombre;
    };
    std::vector<const Ruta*> candidatas(std::string_view camino) const;
    std::vector<Ruta> rutas_;
};

Vistas trocear(std::string_view camino);

}
