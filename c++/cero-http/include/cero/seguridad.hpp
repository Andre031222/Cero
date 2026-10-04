#pragma once

#include <chrono>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

#include "cero/respuesta.hpp"

namespace cero {

// ── Cabeceras de seguridad · SEG-001 a SEG-007 ──────────────────────────────
//
// Lo que el framework hace por la aplicación sin que la aplicación lo pida. El contrato fija el
// comportamiento **por defecto**, no la política: un framework cuyo defecto es inseguro traslada
// al programador una decisión que ese programador no sabe que está tomando.

struct Proteccion {
    // SEG-007: aflojar el enmarcado tiene que poder hacerse sin tocar el resto.
    std::string enmarcado = "DENY";
    // SEG-006: sin CSP declarada no se inventa una. Una política adivinada rompe la aplicación y
    // enseña a desactivarla, que es peor que no tenerla.
    std::optional<std::string> csp;

    Pares aplicar(bool seguro) const;
};

// ── CORS · SEG-008 a SEG-014 ────────────────────────────────────────────────

struct Cors {
    // Vacío significa comodín: cualquier origen.
    std::vector<std::string> origenes;
    bool credenciales = false;
    std::vector<std::string> metodos{"GET", "POST", "PUT", "DELETE", "OPTIONS"};
    std::vector<std::string> cabeceras{"Content-Type", "X-CSRF-Token"};
    unsigned max_age = 600;

    // El preflight y la petición simple se tratan distinto a propósito: ver SEG-010 y SEG-013.
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

// ── CSRF · SEG-015 a SEG-019 ────────────────────────────────────────────────

inline constexpr std::string_view kClaveCsrf = "csrf";
inline constexpr std::string_view kCampoCsrf = "_csrf";

// SEG-019: la exención casa por **segmento completo**, nunca por prefijo pelado. Eximir
// `/api/publico` no puede eximir `/api/publicoSECRETO`: fue un hallazgo de auditoría real.
bool exento(std::string_view camino, const std::vector<std::string>& exenciones);

bool csrf_valido(std::string_view metodo, std::string_view camino,
                 const std::vector<std::string>& exenciones,
                 std::optional<std::string_view> token_sesion,
                 std::optional<std::string_view> token_peticion);

// ── Límite de peticiones · SEG-020 a SEG-022 ────────────────────────────────

struct Veredicto {
    bool permitida;
    unsigned limite;
    unsigned restante;
    unsigned reintentar_en;
};

class Limitador {
public:
    Limitador(unsigned cupo, std::chrono::seconds ventana) : cupo_(cupo), ventana_(ventana) {}

    // La clave es **solo** el cliente. SEG-022: meter la ruta dentro daba cupo nuevo con solo
    // cambiar de camino, y además hacía crecer el mapa sin tope con rutas inventadas — no era un
    // límite esquivable, era agotamiento de memoria.
    Veredicto pedir(std::string_view cliente);
    std::size_t claves() const;

private:
    struct Cuenta {
        unsigned usadas;
        std::chrono::steady_clock::time_point desde;
    };
    mutable std::mutex candado_;
    std::map<std::string, Cuenta, std::less<>> cuentas_;
    unsigned cupo_;
    std::chrono::seconds ventana_;
};

Pares cabeceras_limite(const Veredicto& v);

// ── Saneado · SEG-023 a SEG-026 ─────────────────────────────────────────────

std::string sanear_html(std::string_view entrada);
std::string sanear_texto(std::string_view entrada);
std::string sanear_nombre(std::string_view entrada);

}  // namespace cero
