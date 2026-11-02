#pragma once

#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <string_view>
#include <thread>
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

inline constexpr std::chrono::seconds kPlazoPorCaso{30};

inline int correr() {
    using Reloj = std::chrono::steady_clock;
    static std::atomic<std::size_t> en{0};
    static std::atomic<Reloj::rep> hasta{Reloj::time_point::max().time_since_epoch().count()};
    std::thread{[] {
        while (Reloj::now().time_since_epoch().count() <= hasta) std::this_thread::sleep_for(std::chrono::milliseconds{100});
        cero::linea("  x {} — no acabó en {} s", casos()[en].nombre, kPlazoPorCaso.count());
        std::fflush(stdout);
        std::_Exit(1);
    }}.detach();
    for (std::size_t i = 0; i < casos().size(); ++i) {
        const auto& c = casos()[i];
        corriendo() = c.nombre;
        en = i;
        hasta = (Reloj::now() + kPlazoPorCaso).time_since_epoch().count();
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

}

#define PRUEBA(nombre)                                                \
    static void nombre();                                             \
    static const prueba::Registra registra_##nombre{#nombre, nombre}; \
    static void nombre()

#define COMPRUEBA(...) prueba::comprueba(__VA_ARGS__)

#define PRUEBAS_MAIN \
    int main() { return prueba::correr(); }
