#include "cero/ruta.hpp"

#include <algorithm>
#include <ranges>

namespace cero {

// RUT-004: la barra final se normaliza, así que `/a/7` y `/a/7/` trocean igual.
std::vector<std::string_view> trocear(std::string_view camino) {
    std::vector<std::string_view> partes;
    for (auto parte : std::views::split(camino, '/')) {
        const std::string_view p{parte};
        if (!p.empty()) partes.push_back(p);
    }
    return partes;
}

namespace {

bool es_variable(std::string_view p) { return p.starts_with('{') && p.ends_with('}'); }

}  // namespace

std::expected<Patron, std::string> Patron::nuevo(std::string_view crudo) {
    const auto partes = trocear(crudo);
    for (std::size_t i = 0; i < partes.size(); ++i) {
        const std::string_view p = partes[i];
        if (p == "*" && i + 1 != partes.size()) return std::unexpected("el comodín solo va al final");
        if (p == "{}") return std::unexpected("variable sin nombre");
        if (p.starts_with('{') != p.ends_with('}')) return std::unexpected("variable sin cerrar");
    }
    return Patron{crudo};
}

std::optional<Captura> Patron::casa(std::string_view camino) const {
    const auto patron = trocear(crudo_);
    const auto partes = trocear(camino);
    Captura captura;
    for (std::size_t i = 0; i < patron.size(); ++i) {
        const std::string_view seg = patron[i];
        // RUT-005: el comodín se lleva el resto, sea un segmento o cinco.
        if (seg == "*") {
            if (i >= partes.size()) {
                captura["*"] = "";
                return captura;
            }
            captura["*"] = std::string{partes[i].data(),
                                       partes.back().data() + partes.back().size()};
            return captura;
        }
        if (i >= partes.size()) return std::nullopt;
        if (es_variable(seg)) {
            captura[std::string{seg.substr(1, seg.size() - 2)}] = std::string{partes[i]};
            continue;
        }
        if (partes[i] != seg) return std::nullopt;
    }
    // RUT-001: ni más segmentos ni menos.
    return partes.size() == patron.size() ? std::optional{captura} : std::nullopt;
}

std::size_t Patron::literales() const {
    const auto partes = trocear(crudo_);
    return std::ranges::count_if(partes, [](std::string_view p) { return p != "*" && !es_variable(p); });
}

std::expected<void, std::string> Router::ruta(std::string_view metodo, std::string_view patron,
                                              std::string_view nombre) {
    auto p = Patron::nuevo(patron);
    if (!p) return std::unexpected(p.error());
    std::string verbo{metodo};
    std::ranges::transform(verbo, verbo.begin(), [](unsigned char c) { return std::toupper(c); });
    rutas_.push_back({std::move(verbo), std::move(*p), std::string{nombre}});
    return {};
}

// RUT-008: entre dos patrones que casan gana el que tiene más literales, así que `/usuarios/nuevo`
// no lo atiende `/usuarios/{id}` — o el formulario de alta acabaría buscando al usuario «nuevo».
std::vector<const Router::Ruta*> Router::candidatas(std::string_view camino) const {
    std::vector<const Ruta*> salida;
    for (const auto& r : rutas_) {
        if (r.patron.casa(camino)) salida.push_back(&r);
    }
    std::ranges::stable_sort(salida, std::ranges::greater{},
                             [](const Ruta* r) { return r->patron.literales(); });
    return salida;
}

Resolucion Router::resolver(std::string_view metodo, std::string_view camino) const {
    // RUT-011: HEAD se resuelve contra la ruta GET del mismo camino.
    const std::string_view buscado = metodo == "HEAD" ? "GET" : metodo;
    const auto candidatas = this->candidatas(camino);

    for (const Ruta* r : candidatas) {
        if (r->metodo == buscado) return Encontrada{r->nombre, *r->patron.casa(camino)};
    }
    if (candidatas.empty()) return NoHay{};

    std::vector<std::string> verbos;
    for (const Ruta* r : candidatas) verbos.push_back(r->metodo);
    if (std::ranges::contains(verbos, "GET") && !std::ranges::contains(verbos, "HEAD")) {
        verbos.emplace_back("HEAD");
    }
    std::ranges::sort(verbos);
    verbos.erase(std::ranges::unique(verbos).begin(), verbos.end());
    return VerboNoPermitido{std::move(verbos)};
}

std::optional<std::string> Router::patron_de(std::string_view metodo,
                                             std::string_view camino) const {
    const std::string_view buscado = metodo == "HEAD" ? "GET" : metodo;
    const auto candidatas = this->candidatas(camino);
    if (candidatas.empty()) return std::nullopt;
    for (const Ruta* r : candidatas) {
        if (r->metodo == buscado) return r->patron.crudo();
    }
    return candidatas.front()->patron.crudo();
}

}  // namespace cero
