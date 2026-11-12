#include "cero/ruta.hpp"

#include <algorithm>
#include <cctype>
#include <expected>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

using namespace std;

namespace cero {

Vistas trocear(string_view camino) {
    Vistas partes;
    while (!camino.empty()) {
        const auto barra = camino.find('/');
        if (const auto parte = camino.substr(0, barra); !parte.empty()) partes.push_back(parte);
        if (barra == string_view::npos) break;
        camino.remove_prefix(barra + 1);
    }
    return partes;
}

namespace {

bool es_variable(string_view p) { return p.starts_with('{') && p.ends_with('}'); }

}

expected<Patron, string> Patron::nuevo(string_view crudo) {
    const auto partes = trocear(crudo);
    for (size_t i = 0; i < partes.size(); ++i) {
        const string_view p = partes[i];
        if (p == "*" && i + 1 != partes.size()) return unexpected("el comodín solo va al final");
        if (p == "{}") return unexpected("variable sin nombre");
        if (p.starts_with('{') != p.ends_with('}')) return unexpected("variable sin cerrar");
    }
    return Patron{crudo};
}

optional<Captura> Patron::casa(string_view camino) const {
    const auto patron = trocear(crudo_);
    const auto partes = trocear(camino);
    Captura captura;
    for (size_t i = 0; i < patron.size(); ++i) {
        const string_view seg = patron[i];
        if (seg == "*") {
            if (i >= partes.size()) {
                captura["*"] = "";
                return captura;
            }
            captura["*"] = string{partes[i].data(), partes.back().data() + partes.back().size()};
            return captura;
        }
        if (i >= partes.size()) return nullopt;
        if (es_variable(seg)) {
            captura[string{seg.substr(1, seg.size() - 2)}] = string{partes[i]};
            continue;
        }
        if (partes[i] != seg) return nullopt;
    }
    return partes.size() == patron.size() ? optional{captura} : nullopt;
}

size_t Patron::literales() const {
    const auto partes = trocear(crudo_);
    return ranges::count_if(partes, [](string_view p) { return p != "*" && !es_variable(p); });
}

expected<void, string> Router::ruta(string_view metodo, string_view patron, string_view nombre) {
    auto p = Patron::nuevo(patron);
    if (!p) return unexpected(p.error());
    string verbo{metodo};
    ranges::transform(verbo, verbo.begin(), [](unsigned char c) { return toupper(c); });
    rutas_.push_back({std::move(verbo), std::move(*p), string{nombre}});
    return {};
}

vector<const Router::Ruta*> Router::candidatas(string_view camino) const {
    vector<const Ruta*> salida;
    for (const auto& r : rutas_) {
        if (r.patron.casa(camino)) salida.push_back(&r);
    }
    ranges::stable_sort(salida, ranges::greater{},
                        [](const Ruta* r) { return r->patron.literales(); });
    return salida;
}

Resolucion Router::resolver(string_view metodo, string_view camino) const {
    const string_view buscado = metodo == "HEAD" ? "GET" : metodo;
    const auto candidatas = this->candidatas(camino);

    for (const Ruta* r : candidatas) {
        if (r->metodo == buscado) return Encontrada{r->nombre, *r->patron.casa(camino)};
    }
    if (candidatas.empty()) return NoHay{};

    Textos verbos;
    for (const Ruta* r : candidatas) verbos.push_back(r->metodo);
    const auto tiene = [&verbos](string_view v) {
        return ranges::find(verbos, v) != verbos.end();
    };
    if (tiene("GET") && !tiene("HEAD")) verbos.emplace_back("HEAD");
    ranges::sort(verbos);
    verbos.erase(ranges::unique(verbos).begin(), verbos.end());
    return VerboNoPermitido{std::move(verbos)};
}

optional<string> Router::patron_de(string_view metodo, string_view camino) const {
    const string_view buscado = metodo == "HEAD" ? "GET" : metodo;
    const auto candidatas = this->candidatas(camino);
    if (candidatas.empty()) return nullopt;
    for (const Ruta* r : candidatas) {
        if (r->metodo == buscado) return r->patron.crudo();
    }
    return candidatas.front()->patron.crudo();
}

}
