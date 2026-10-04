#pragma once

#include <expected>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

namespace cero {

using Captura = std::map<std::string, std::string, std::less<>>;

// El contrato dice qué resuelve el router, no cómo se le dice qué rutas tiene. Java lo hace con
// anotaciones leídas por reflexión; aquí se registran, y los requisitos se cumplen igual.
class Patron {
public:
    // RUT-006 y RUT-007: un patrón inválido no llega a existir. Detectado al arrancar es un error
    // del programador; detectado al resolver es un 500 en producción.
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
// RUT-009: el camino existe pero no con ese verbo. No es un 404, y RUT-010 pide poder enumerar
// los que sí valen.
struct VerboNoPermitido {
    std::vector<std::string> verbos;
};
struct NoHay {};

using Resolucion = std::variant<Encontrada, VerboNoPermitido, NoHay>;

class Router {
public:
    std::expected<void, std::string> ruta(std::string_view metodo, std::string_view patron,
                                          std::string_view nombre);
    Resolucion resolver(std::string_view metodo, std::string_view camino) const;

    // El patrón que atendió, para que las métricas agrupen por él y no por la URL.
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

std::vector<std::string_view> trocear(std::string_view camino);

}  // namespace cero
