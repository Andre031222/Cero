#include "cero/observabilidad.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <functional>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "cero/formato.hpp"

using namespace std;

namespace cero {
namespace {

string comillas(const Textos& v) {
    string r;
    for (const auto& s : v) r += (r.empty() ? "" : ",") + formato("\"{}\"", s);
    return r;
}

uint64_t segundos_desde(chrono::steady_clock::time_point t) {
    using namespace chrono;
    return static_cast<uint64_t>(duration_cast<seconds>(steady_clock::now() - t).count());
}

}

Salud& Salud::comprobacion(string_view nombre, function<Comprobado()> f) {
    comprobaciones_.emplace_back(string{nombre}, std::move(f));
    return *this;
}

Informe Salud::vivo() const {
    return {200, formato("{{\"vivo\":true,\"activo_s\":{}}}", segundos_desde(arranque_))};
}

Informe Salud::listo() const {
    Textos van;
    Textos fallan;
    for (const auto& [nombre, f] : comprobaciones_) {
        Comprobado r = Comprobado::falla("la comprobación lanzó");
        try {
            r = f();
        } catch (...) {
        }
        if (r.bien) {
            van.push_back(nombre);
        } else {
            fallan.push_back(formato("{{\"nombre\":\"{}\",\"motivo\":\"{}\"}}", nombre, r.motivo));
        }
    }

    if (fallan.empty()) {
        if (publico) return {200, "{\"listo\":true}"};
        return {200, formato("{{\"listo\":true,\"comprobaciones\":[{}]}}", comillas(van))};
    }
    if (publico) return {503, "{\"listo\":false}"};
    string detalle;
    for (const auto& f : fallan) detalle += (detalle.empty() ? "" : ",") + f;
    return {503, formato("{{\"listo\":false,\"fallan\":[{}],\"van\":[{}]}}", detalle, comillas(van))};
}

string interpolar(string_view plantilla, const Vistas& valores) {
    string salida;
    salida.reserve(plantilla.size());
    size_t i = 0;
    while (true) {
        const auto marca = plantilla.find("{}");
        if (marca == string_view::npos) break;
        salida += plantilla.substr(0, marca);
        salida += i < valores.size() ? valores[i] : string_view{"{}"};
        ++i;
        plantilla.remove_prefix(marca + 2);
    }
    salida += plantilla;
    return salida;
}

namespace {

string_view nombre_de(Nivel n) {
    switch (n) {
        case Nivel::Traza: return "Traza";
        case Nivel::Depuracion: return "Depuracion";
        case Nivel::Info: return "Info";
        case Nivel::Aviso: return "Aviso";
        case Nivel::Error: return "Error";
        case Nivel::Nada: return "Nada";
    }
    return "?";
}

}

void Log::escribir(Nivel nivel, string_view plantilla, const Vistas& valores) {
    if (nivel < nivel_ || nivel_ == Nivel::Nada) return;
    auto linea = formato("{} {} {}", nombre_de(nivel), origen_, interpolar(plantilla, valores));
    const lock_guard tomado{candado_};
    lineas_.push_back(std::move(linea));
}

void Log::con_error(string_view plantilla, const Vistas& valores,
                    string_view tipo, string_view mensaje) {
    escribir(Nivel::Error, formato("{}: {}: {}", plantilla, tipo, mensaje), valores);
}

Textos Log::lineas() const {
    const lock_guard tomado{candado_};
    return lineas_;
}

void Metricas::ignorar(string_view patron) {
    const unique_lock escritura{candado_};
    ignoradas_.emplace_back(patron);
}

void Metricas::anotar(string_view patron, unsigned estado, chrono::microseconds tardo) {
    const unique_lock escritura{candado_};
    if (ranges::find(ignoradas_, patron) != ignoradas_.end()) return;
    total_.fetch_add(1, memory_order_relaxed);
    auto& c = por_patron_.try_emplace(string{patron}).first->second;
    ++c.peticiones;
    if (estado >= 400) ++c.errores;
    c.micros.push_back(static_cast<uint64_t>(tardo.count()));
}

uint64_t Metricas::peticiones(string_view patron) const {
    const shared_lock lectura{candado_};
    const auto i = por_patron_.find(patron);
    return i == por_patron_.end() ? 0 : i->second.peticiones;
}

uint64_t Metricas::errores(string_view patron) const {
    const shared_lock lectura{candado_};
    const auto i = por_patron_.find(patron);
    return i == por_patron_.end() ? 0 : i->second.errores;
}

optional<uint64_t> Metricas::percentil(string_view patron, double p) const {
    const shared_lock lectura{candado_};
    const auto i = por_patron_.find(patron);
    if (i == por_patron_.end() || i->second.micros.empty()) return nullopt;
    auto v = i->second.micros;
    ranges::sort(v);
    const auto donde = static_cast<size_t>(
        llround((static_cast<double>(v.size()) - 1.0) * p));
    return v[donde];
}

size_t Metricas::patrones() const {
    const shared_lock lectura{candado_};
    return por_patron_.size();
}

string Metricas::json() const {
    const shared_lock lectura{candado_};
    string rutas;
    for (const auto& [patron, c] : por_patron_) {
        rutas += (rutas.empty() ? "" : ",") +
                 formato("{{\"ruta\":\"{}\",\"peticiones\":{},\"errores\":{}}}", patron,
                       c.peticiones, c.errores);
    }
    return formato("{{\"total\":{},\"activo_s\":{},\"rutas\":[{}]}}",
                 total_.load(memory_order_relaxed), segundos_desde(arranque_), rutas);
}

string linea_acceso(string_view metodo, string_view destino, unsigned estado,
                    optional<string_view> usuario,
                    chrono::microseconds tardo) {
    using namespace chrono;
    return formato("{} {} {} {} {}ms", metodo, destino, estado, usuario.value_or("-"),
                 duration_cast<milliseconds>(tardo).count());
}

}
