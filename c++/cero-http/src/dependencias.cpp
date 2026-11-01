#include "cero/dependencias.hpp"

#include <algorithm>
#include <mutex>
#include <string>
#include <typeindex>
#include <utility>
#include <vector>

#include "cero/fallo.hpp"

using namespace std;

namespace cero {
namespace {

struct EnCurso {
    const Dependencias* contenedor;
    type_index tipo;

    bool operator==(const EnCurso&) const = default;
};

thread_local vector<EnCurso> pila;

string camino(const Dependencias* contenedor, type_index cierre) {
    string texto;
    for (const auto& e : pila) {
        if (e.contenedor == contenedor) texto += string{e.tipo.name()} + " → ";
    }
    return texto + cierre.name();
}

}

Dependencias::Hecho Dependencias::resolver(type_index tipo) {
    {
        const lock_guard tomado{candado_};
        if (const auto hecho = hechos_.find(tipo); hecho != hechos_.end()) return hecho->second;
    }
    const auto receta = recetas_.find(tipo);
    if (receta == recetas_.end()) {
        throw Fallo::interno(string{"no hay nada registrado para "} + tipo.name());
    }
    const EnCurso este{this, tipo};
    if (ranges::find(pila, este) != pila.end()) {
        throw Fallo::interno("ciclo de dependencias: " + camino(this, tipo));
    }

    pila.push_back(este);
    const struct Salida {
        ~Salida() { pila.pop_back(); }
    } salida;
    auto hecho = receta->second(*this);

    const lock_guard tomado{candado_};
    return hechos_.try_emplace(tipo, std::move(hecho)).first->second;
}

}
