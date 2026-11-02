#include "cero/dependencias.hpp"

#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <typeindex>
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
    Fabrica fabrica;
    shared_ptr<Obra> obra;
    {
        const lock_guard tomado{candado_};
        const auto receta = recetas_.find(tipo);
        if (receta == recetas_.end()) {
            throw Fallo::interno(string{"no hay nada registrado para "} + tipo.name());
        }
        fabrica = receta->second;
        auto& sitio = obras_[tipo];
        if (!sitio) sitio = make_shared<Obra>();
        obra = sitio;
    }
    const EnCurso este{this, tipo};
    if (ranges::find(pila, este) != pila.end()) {
        throw Fallo::interno("ciclo de dependencias: " + camino(this, tipo));
    }

    pila.push_back(este);
    const struct Salida {
        ~Salida() { pila.pop_back(); }
    } salida;
    const lock_guard construyendo{obra->candado};
    if (!obra->hecho) obra->hecho = fabrica(*this);
    return obra->hecho;
}

}
