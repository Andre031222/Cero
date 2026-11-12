#pragma once

#include <expected>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

#include "cero/tipos.hpp"

namespace cero {

class Json {
public:
    struct Nulo {
        bool operator==(const Nulo&) const = default;
    };
    using Objeto = PorNombre<Json>;
    using Lista = std::vector<Json>;

    Json() = default;
    Json(bool v) : valor_(v) {}
    Json(double v) : valor_(v) {}
    Json(int v) : valor_(static_cast<double>(v)) {}
    Json(long long v) : valor_(static_cast<double>(v)) {}
    Json(std::string v) : valor_(std::move(v)) {}
    Json(std::string_view v) : valor_(std::string{v}) {}
    Json(const char* v) : valor_(std::string{v}) {}
    Json(Lista v) : valor_(std::move(v)) {}
    Json(Objeto v) : valor_(std::move(v)) {}

    static Json objeto(std::vector<std::pair<std::string, Json>> pares);

    const Json* get(std::string_view clave) const;
    std::optional<std::string_view> cadena() const;
    std::optional<double> numero() const;
    std::optional<long long> entero() const;
    std::optional<bool> booleano() const;
    const Lista* lista() const;
    const Objeto* como_objeto() const;

    std::string escribir() const;

    bool operator==(const Json&) const = default;

private:
    std::variant<Nulo, bool, double, std::string, Lista, Objeto> valor_;
};

struct FalloJson {
    std::string porque;
};

std::expected<Json, FalloJson> leer(std::string_view entrada);

}
