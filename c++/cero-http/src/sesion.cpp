#include "cero/sesion.hpp"

#include <array>
#include <cstdio>

#include "cero/texto.hpp"

namespace cero {

std::optional<std::string> identificador() {
    std::array<unsigned char, 32> crudo{};
    std::FILE* fuente = std::fopen("/dev/urandom", "rb");
    if (fuente == nullptr) return std::nullopt;
    const auto leidos = std::fread(crudo.data(), 1, crudo.size(), fuente);
    std::fclose(fuente);
    if (leidos != crudo.size()) return std::nullopt;

    // base16 da 64 caracteres de 32 octetos: por encima del mínimo y sin alfabeto ambiguo.
    std::string id;
    id.reserve(crudo.size() * 2);
    for (unsigned char b : crudo) id += texto("{:02x}", b);
    return id;
}

bool Sesion::poner(std::string_view clave, std::string_view valor) {
    if (!viva_) return false;
    atributos_[std::string{clave}] = std::string{valor};
    tocada_ = Reloj::now();
    sucia_ = true;
    return true;
}

std::optional<std::string_view> Sesion::leer(std::string_view clave) const {
    if (!viva_) return std::nullopt;
    const auto i = atributos_.find(clave);
    if (i == atributos_.end()) return std::nullopt;
    return i->second;
}

void Sesion::invalidar() {
    viva_ = false;
    atributos_.clear();
    sucia_ = true;
}

std::optional<std::string> Sesion::cookie_pendiente() {
    if (!cookie_pendiente_) return std::nullopt;
    cookie_pendiente_ = false;
    return id_;
}

bool Sesion::sucia() { return std::exchange(sucia_, false); }

std::shared_ptr<Sesion> Sesion::rescatada(std::string_view id,
                                          std::map<std::string, std::string, std::less<>> atributos,
                                          Reloj::time_point creada, Reloj::time_point tocada) {
    auto s = std::make_shared<Sesion>();
    s->id_ = std::string{id};
    s->atributos_ = std::move(atributos);
    s->creada_ = creada;
    s->tocada_ = tocada;
    // Ya estaba guardada y el cliente ya tiene su cookie: es la que usó para llegar hasta aquí.
    s->cookie_pendiente_ = false;
    s->sucia_ = false;
    return s;
}

bool Almacen::caducada(Reloj::time_point creada, Reloj::time_point tocada) const {
    const auto ahora = Reloj::now();
    if (ahora - tocada > inactividad_) return true;
    return vida_maxima_ && ahora - creada > *vida_maxima_;
}

// SES-001: sin cookie no se recupera nada. Devolver una sesión nueva aquí es lo que convierte a
// cualquier rastreador en un generador de sesiones huérfanas.
std::optional<Guardada> Almacen::recuperar(std::optional<std::string_view> id) {
    if (!id) return std::nullopt;
    std::optional<Guardada> encontrada;
    {
        std::shared_lock lectura{candado_};
        const auto i = sesiones_.find(*id);
        if (i == sesiones_.end()) return std::nullopt;
        encontrada = i->second;
    }
    const auto g = encontrada->tomar();
    const auto& s = *encontrada->sesion;
    if (s.viva() && !caducada(s.creada(), s.tocada())) return encontrada;

    std::unique_lock escritura{candado_};
    sesiones_.erase(std::string{*id});
    return std::nullopt;
}

std::optional<Guardada> Almacen::crear() {
    const auto id = identificador();
    if (!id) return std::nullopt;
    auto sesion = std::make_shared<Sesion>();
    sesion->id_ = *id;
    sesion->creada_ = Reloj::now();
    sesion->tocada_ = sesion->creada_;
    const Guardada guardada{std::move(sesion), std::make_shared<std::mutex>()};

    std::unique_lock escritura{candado_};
    sesiones_.emplace(*id, guardada);
    return guardada;
}

// SES-005: cambia el identificador conservando los atributos y obliga a reemitir la cookie.
// SES-006: una invalidada no se rota.
std::optional<std::string> Almacen::rotar(const Guardada& g) {
    std::string viejo;
    std::string nuevo;
    {
        const auto tomado = g.tomar();
        if (!g.sesion->viva()) return std::nullopt;
        const auto id = identificador();
        if (!id) return std::nullopt;
        viejo = g.sesion->id_;
        nuevo = *id;
        g.sesion->id_ = nuevo;
        g.sesion->cookie_pendiente_ = true;
        g.sesion->sucia_ = true;
    }
    std::unique_lock escritura{candado_};
    sesiones_.erase(viejo);
    sesiones_.emplace(nuevo, g);
    return nuevo;
}

std::size_t Almacen::cuantas() const {
    std::shared_lock lectura{candado_};
    return sesiones_.size();
}

std::string cabecera_cookie(std::string_view id, bool seguro) {
    auto c = texto("cero_sid={}; Path=/; HttpOnly; SameSite=Lax", id);
    if (seguro) c += "; Secure";
    return c;
}

std::optional<std::string_view> id_de_cookie(std::optional<std::string_view> cabecera) {
    if (!cabecera) return std::nullopt;
    std::string_view resto = *cabecera;
    while (!resto.empty()) {
        const auto fin = resto.find(';');
        std::string_view par = resto.substr(0, fin);
        while (par.starts_with(' ')) par.remove_prefix(1);
        if (par.starts_with("cero_sid=")) return par.substr(9);
        if (fin == std::string_view::npos) break;
        resto.remove_prefix(fin + 1);
    }
    return std::nullopt;
}

}  // namespace cero
