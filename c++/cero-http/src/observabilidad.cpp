#include "cero/observabilidad.hpp"

#include <algorithm>

#include "cero/texto.hpp"

namespace cero {
namespace {

std::string comillas(const std::vector<std::string>& v) {
    std::string r;
    for (const auto& s : v) r += (r.empty() ? "" : ",") + texto("\"{}\"", s);
    return r;
}

std::uint64_t segundos_desde(std::chrono::steady_clock::time_point t) {
    using namespace std::chrono;
    return static_cast<std::uint64_t>(duration_cast<seconds>(steady_clock::now() - t).count());
}

}  // namespace

Salud& Salud::comprobacion(std::string_view nombre, std::function<Comprobado()> f) {
    comprobaciones_.emplace_back(std::string{nombre}, std::move(f));
    return *this;
}

Informe Salud::vivo() const {
    return {200, texto("{{\"vivo\":true,\"activo_s\":{}}}", segundos_desde(arranque_))};
}

Informe Salud::listo() const {
    std::vector<std::string> van;
    std::vector<std::string> fallan;
    for (const auto& [nombre, f] : comprobaciones_) {
        // OBS-005: una comprobación que lanza da 503, no 500. Lanzar es una forma de fallar, no un
        // fallo del endpoint, y en C++ eso se atrapa aquí en vez de dejarlo subir.
        Comprobado r = Comprobado::falla("la comprobación lanzó");
        try {
            r = f();
        } catch (...) {
        }
        if (r.bien) {
            van.push_back(nombre);
        } else {
            fallan.push_back(texto("{{\"nombre\":\"{}\",\"motivo\":\"{}\"}}", nombre, r.motivo));
        }
    }

    if (fallan.empty()) {
        // OBS-007: en modo público y con todo en verde, solo que está listo.
        if (publico) return {200, "{\"listo\":true}"};
        return {200, texto("{{\"listo\":true,\"comprobaciones\":[{}]}}", comillas(van))};
    }
    // OBS-003 y OBS-004: 503 diciendo cuál falló y sin ocultar las que sí van. OBS-006: en modo
    // público el código es el mismo y el detalle no sale.
    if (publico) return {503, "{\"listo\":false}"};
    std::string detalle;
    for (const auto& f : fallan) detalle += (detalle.empty() ? "" : ",") + f;
    return {503, texto("{{\"listo\":false,\"fallan\":[{}],\"van\":[{}]}}", detalle, comillas(van))};
}

std::string interpolar(std::string_view plantilla,
                       const std::vector<std::string_view>& valores) {
    std::string salida;
    salida.reserve(plantilla.size());
    std::size_t i = 0;
    while (true) {
        const auto marca = plantilla.find("{}");
        if (marca == std::string_view::npos) break;
        salida += plantilla.substr(0, marca);
        // Con valores de menos se conserva el marcador: un log al que le falta un dato tiene que
        // decir que le falta, no borrar el hueco y mentir sobre el formato.
        salida += i < valores.size() ? valores[i] : std::string_view{"{}"};
        ++i;
        plantilla.remove_prefix(marca + 2);
    }
    salida += plantilla;
    return salida;
}

namespace {

std::string_view nombre_de(Nivel n) {
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

}  // namespace

void Log::escribir(Nivel nivel, std::string_view plantilla,
                   const std::vector<std::string_view>& valores) {
    // OBS-009: filtra por debajo y deja pasar por encima, y `Nada` calla todo.
    if (nivel < nivel_ || nivel_ == Nivel::Nada) return;
    // OBS-008: nivel, origen y los valores ya interpolados.
    auto linea = texto("{} {} {}", nombre_de(nivel), origen_, interpolar(plantilla, valores));
    const std::lock_guard tomado{candado_};
    lineas_.push_back(std::move(linea));
}

void Log::con_error(std::string_view plantilla, const std::vector<std::string_view>& valores,
                    std::string_view tipo, std::string_view mensaje) {
    escribir(Nivel::Error, texto("{}: {}: {}", plantilla, tipo, mensaje), valores);
}

std::vector<std::string> Log::lineas() const {
    const std::lock_guard tomado{candado_};
    return lineas_;
}

void Metricas::ignorar(std::string_view patron) {
    const std::unique_lock escritura{candado_};
    ignoradas_.emplace_back(patron);
}

void Metricas::anotar(std::string_view patron, unsigned estado, std::chrono::microseconds tardo) {
    const std::unique_lock escritura{candado_};
    // OBS-017: las declaradas ignoradas no se cuentan.
    if (std::ranges::find(ignoradas_, patron) != ignoradas_.end()) return;
    total_.fetch_add(1, std::memory_order_relaxed);
    auto& c = por_patron_.try_emplace(std::string{patron}).first->second;
    ++c.peticiones;
    // OBS-016: un 404 cuenta como error. Es entrada del cliente, pero también es la señal de que
    // alguien enlazó mal algo nuestro.
    if (estado >= 400) ++c.errores;
    c.micros.push_back(static_cast<std::uint64_t>(tardo.count()));
}

std::uint64_t Metricas::peticiones(std::string_view patron) const {
    const std::shared_lock lectura{candado_};
    const auto i = por_patron_.find(patron);
    return i == por_patron_.end() ? 0 : i->second.peticiones;
}

std::uint64_t Metricas::errores(std::string_view patron) const {
    const std::shared_lock lectura{candado_};
    const auto i = por_patron_.find(patron);
    return i == por_patron_.end() ? 0 : i->second.errores;
}

std::optional<std::uint64_t> Metricas::percentil(std::string_view patron, double p) const {
    const std::shared_lock lectura{candado_};
    const auto i = por_patron_.find(patron);
    if (i == por_patron_.end() || i->second.micros.empty()) return std::nullopt;
    auto v = i->second.micros;
    std::ranges::sort(v);
    const auto donde = static_cast<std::size_t>(
        std::llround((static_cast<double>(v.size()) - 1.0) * p));
    return v[donde];
}

std::size_t Metricas::patrones() const {
    const std::shared_lock lectura{candado_};
    return por_patron_.size();
}

// OBS-018: una exposición legible por máquina, con el total y el detalle por ruta.
std::string Metricas::json() const {
    const std::shared_lock lectura{candado_};
    std::string rutas;
    for (const auto& [patron, c] : por_patron_) {
        rutas += (rutas.empty() ? "" : ",") +
                 texto("{{\"ruta\":\"{}\",\"peticiones\":{},\"errores\":{}}}", patron,
                       c.peticiones, c.errores);
    }
    return texto("{{\"total\":{},\"activo_s\":{},\"rutas\":[{}]}}",
                 total_.load(std::memory_order_relaxed), segundos_desde(arranque_), rutas);
}

std::string linea_acceso(std::string_view metodo, std::string_view destino, unsigned estado,
                         std::optional<std::string_view> usuario,
                         std::chrono::microseconds tardo) {
    using namespace std::chrono;
    return texto("{} {} {} {} {}ms", metodo, destino, estado, usuario.value_or("-"),
                 duration_cast<milliseconds>(tardo).count());
}

}  // namespace cero
