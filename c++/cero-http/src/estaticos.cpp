#include "cero/estaticos.hpp"

#include <algorithm>
#include <fstream>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <utility>

namespace cero {
namespace fs = std::filesystem;

std::optional<Estaticos> Estaticos::en(std::string_view raiz) {
    std::error_code fallo;
    auto real = fs::canonical(fs::path{raiz}, fallo);
    if (fallo) return std::nullopt;
    return Estaticos{std::move(real)};
}

Estaticos&& Estaticos::con_respaldo(std::string_view archivo) && {
    respaldo_ = std::string{archivo};
    return std::move(*this);
}

Respuesta Estaticos::servir(std::string_view camino) const {
    while (camino.starts_with('/')) camino.remove_prefix(1);

    // Se descartan `..` y las raíces antes de tocar el disco: así un camino hostil no llega
    // siquiera a resolverse.
    fs::path limpio;
    for (const auto& parte : fs::path{camino}) {
        const auto t = parte.string();
        if (t.empty() || t == "." || t == ".." || t == "/" || t == "\\") continue;
        limpio /= t;
    }

    std::error_code fallo;
    const auto real = fs::canonical(raiz_ / limpio, fallo);
    if (fallo) return respaldar();

    // La comprobación que de verdad importa: tras resolver enlaces, ¿sigue dentro? Filtrar `..`
    // antes no basta, porque un enlace simbólico dentro de la raíz apunta fuera sin que aparezca
    // ningún `..`.
    const auto dentro = std::mismatch(raiz_.begin(), raiz_.end(), real.begin(), real.end()).first;
    if (dentro != raiz_.end()) return Respuesta::codigo(403, "fuera de la raíz");

    std::ifstream archivo{real, std::ios::binary};
    if (!archivo) return respaldar();
    std::ostringstream datos;
    datos << archivo.rdbuf();

    Respuesta r{200, std::string{tipo_de(real)}, datos.str(), {}};
    r.extra.emplace_back("Cache-Control", "public, max-age=3600");
    return r;
}

Respuesta Estaticos::respaldar() const {
    if (!respaldo_) return Respuesta::codigo(404, "no encontrado");
    std::ifstream archivo{raiz_ / *respaldo_, std::ios::binary};
    if (!archivo) return Respuesta::codigo(404, "no encontrado");
    std::ostringstream datos;
    datos << archivo.rdbuf();
    return Respuesta::html(datos.str());
}

std::string_view tipo_de(const std::filesystem::path& ruta) {
    const auto ext = ruta.extension().string();
    if (ext == ".html" || ext == ".htm") return "text/html; charset=utf-8";
    if (ext == ".css") return "text/css; charset=utf-8";
    if (ext == ".js" || ext == ".mjs") return "text/javascript; charset=utf-8";
    if (ext == ".json") return "application/json; charset=utf-8";
    if (ext == ".svg") return "image/svg+xml";
    if (ext == ".png") return "image/png";
    if (ext == ".jpg" || ext == ".jpeg") return "image/jpeg";
    if (ext == ".webp") return "image/webp";
    if (ext == ".woff2") return "font/woff2";
    if (ext == ".txt" || ext == ".md") return "text/plain; charset=utf-8";
    // Lo que no se reconoce va como octetos, nunca adivinando: adivinar el tipo es justo lo que
    // `X-Content-Type-Options: nosniff` existe para impedir del lado del navegador.
    return "application/octet-stream";
}

}  // namespace cero
