#pragma once

#include <chrono>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <variant>

#include "cero/respuesta.hpp"
#include "cero/tipos.hpp"

namespace cero {

struct Proteccion {
    std::string enmarcado = "DENY";
    std::optional<std::string> csp;

    Pares aplicar(bool seguro) const;
};

struct Cors {
    Textos origenes;
    bool credenciales = false;
    Textos metodos{"GET", "POST", "PUT", "DELETE", "OPTIONS"};
    Textos cabeceras{"Content-Type", "X-CSRF-Token"};
    unsigned max_age = 600;

    struct Sigue {
        Pares cabeceras;
    };
    struct Corta {
        unsigned estado;
        Pares cabeceras;
    };
    using Decision = std::variant<Sigue, Corta>;

    Decision decidir(std::string_view metodo, std::optional<std::string_view> origen) const;
};

inline constexpr std::string_view kClaveCsrf = "csrf";
inline constexpr std::string_view kCampoCsrf = "_csrf";

bool exento(std::string_view camino, const Textos& exenciones);

bool csrf_valido(std::string_view metodo, std::string_view camino, const Textos& exenciones,
                 std::optional<std::string_view> token_sesion,
                 std::optional<std::string_view> token_peticion);

struct Veredicto {
    bool permitida;
    unsigned limite;
    unsigned restante;
    unsigned reintentar_en;
};

class Limitador {
public:
    Limitador(unsigned cupo, std::chrono::seconds ventana) : cupo_(cupo), ventana_(ventana) {}

    Veredicto pedir(std::string_view cliente);
    std::size_t claves() const;

private:
    struct Cuenta {
        unsigned usadas;
        std::chrono::steady_clock::time_point desde;
    };
    mutable std::mutex candado_;
    PorNombre<Cuenta> cuentas_;
    unsigned cupo_;
    std::chrono::seconds ventana_;
};

Pares cabeceras_limite(const Veredicto& v);

std::string sanear_html(std::string_view entrada);
std::string sanear_texto(std::string_view entrada);
std::string sanear_nombre(std::string_view entrada);

}
