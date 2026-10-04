#pragma once

// El corredor de pruebas, en cuarenta líneas. Catch2 o GoogleTest serían la primera dependencia
// del proyecto, y Java tiene el suyo propio por este mismo motivo.

#include <cstddef>
#include <string_view>
#include <vector>

#include "cero/formato.hpp"

namespace prueba {

struct Caso {
    std::string_view nombre;
    void (*cuerpo)();
};

inline std::vector<Caso>& casos() {
    static std::vector<Caso> v;
    return v;
}

inline int& fallos() {
    static int n = 0;
    return n;
}

inline std::string_view& corriendo() {
    static std::string_view n;
    return n;
}

inline void comprueba(bool bien, std::string_view porque) {
    if (bien) return;
    ++fallos();
    cero::linea("  x {} — {}", corriendo(), porque);
}

inline int correr() {
    for (const auto& c : casos()) {
        corriendo() = c.nombre;
        const int antes = fallos();
        c.cuerpo();
        if (fallos() == antes) cero::linea("  ok {}", c.nombre);
    }
    cero::linea("{} casos · {} fallos", casos().size(), fallos());
    return fallos() == 0 ? 0 : 1;
}

struct Registra {
    Registra(std::string_view nombre, void (*cuerpo)()) { casos().push_back({nombre, cuerpo}); }
};

}  // namespace prueba

#define PRUEBA(nombre)                                                \
    static void nombre();                                             \
    static const prueba::Registra registra_##nombre{#nombre, nombre}; \
    static void nombre()

// Variádico y no de dos argumentos: con dos, una condición que lleve llaves dentro —`T{a, b}`—
// parte el macro por la coma y el error que sale no habla de eso. Al reenviar todo tal cual, la
// coma vuelve a ser la coma de la llamada y la llave se escribe donde toca.
#define COMPRUEBA(...) prueba::comprueba(__VA_ARGS__)

#define PRUEBAS_MAIN \
    int main() { return prueba::correr(); }
