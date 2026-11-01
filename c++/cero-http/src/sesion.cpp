#include "cero/sesion.hpp"

#include <array>
#include <cstdio>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <utility>

#include "cero/formato.hpp"

using namespace std;

namespace cero {

optional<string> identificador() {
    array<unsigned char, 32> crudo{};
    FILE* fuente = fopen("/dev/urandom", "rb");
    if (fuente == nullptr) return nullopt;
    const auto leidos = fread(crudo.data(), 1, crudo.size(), fuente);
    fclose(fuente);
    if (leidos != crudo.size()) return nullopt;

    string id;
    id.reserve(crudo.size() * 2);
    for (unsigned char b : crudo) id += formato("{:02x}", b);
    return id;
}

bool Sesion::poner(string_view clave, string_view valor) {
    if (!viva_) return false;
    atributos_[string{clave}] = string{valor};
    tocada_ = Reloj::now();
    sucia_ = true;
    return true;
}

optional<string_view> Sesion::leer(string_view clave) const {
    if (!viva_) return nullopt;
    const auto i = atributos_.find(clave);
    if (i == atributos_.end()) return nullopt;
    return i->second;
}

void Sesion::invalidar() {
    viva_ = false;
    atributos_.clear();
    sucia_ = true;
}

optional<string> Sesion::cookie_pendiente() {
    if (!cookie_pendiente_) return nullopt;
    cookie_pendiente_ = false;
    return id_;
}

bool Sesion::sucia() { return exchange(sucia_, false); }

optional<string> Sesion::renovar_id() {
    if (!viva_) return nullopt;
    auto id = identificador();
    if (!id) return nullopt;
    id_ = *id;
    cookie_pendiente_ = true;
    sucia_ = true;
    return id;
}

shared_ptr<Sesion> Sesion::nueva() {
    const auto id = identificador();
    if (!id) return nullptr;
    auto s = make_shared<Sesion>();
    s->id_ = *id;
    s->creada_ = Reloj::now();
    s->tocada_ = s->creada_;
    return s;
}

shared_ptr<Sesion> Sesion::rescatada(string_view id, Atributos atributos,
                                          Reloj::time_point creada, Reloj::time_point tocada) {
    auto s = make_shared<Sesion>();
    s->id_ = string{id};
    s->atributos_ = std::move(atributos);
    s->creada_ = creada;
    s->tocada_ = tocada;
    s->cookie_pendiente_ = false;
    s->sucia_ = false;
    return s;
}

bool Caducidad::vencida(Reloj::time_point creada, Reloj::time_point tocada) const {
    const auto ahora = Reloj::now();
    if (ahora - tocada > inactividad) return true;
    return vida_maxima && ahora - creada > *vida_maxima;
}

optional<Guardada> Almacen::recuperar(optional<string_view> id) {
    if (!id) return nullopt;
    optional<Guardada> encontrada;
    {
        shared_lock lectura{candado_};
        const auto i = sesiones_.find(*id);
        if (i == sesiones_.end()) return nullopt;
        encontrada = i->second;
    }
    const auto g = encontrada->tomar();
    const auto& s = *encontrada->sesion;
    if (s.viva() && !caducidad_.vencida(s.creada(), s.tocada())) return encontrada;

    unique_lock escritura{candado_};
    sesiones_.erase(string{*id});
    return nullopt;
}

optional<Guardada> Almacen::crear() {
    auto sesion = Sesion::nueva();
    if (!sesion) return nullopt;
    const Guardada guardada{std::move(sesion), make_shared<mutex>()};

    unique_lock escritura{candado_};
    sesiones_.emplace(guardada.sesion->id(), guardada);
    return guardada;
}

optional<string> Almacen::rotar(const Guardada& g) {
    string viejo;
    optional<string> nuevo;
    {
        const auto tomado = g.tomar();
        viejo = g.sesion->id();
        nuevo = g.sesion->renovar_id();
    }
    if (!nuevo) return nullopt;
    unique_lock escritura{candado_};
    sesiones_.erase(viejo);
    sesiones_.emplace(*nuevo, g);
    return nuevo;
}

size_t Almacen::cuantas() const {
    shared_lock lectura{candado_};
    return sesiones_.size();
}

string cabecera_cookie(string_view id, bool seguro) {
    auto c = formato("cero_sid={}; Path=/; HttpOnly; SameSite=Lax", id);
    if (seguro) c += "; Secure";
    return c;
}

optional<string_view> id_de_cookie(optional<string_view> cabecera) {
    if (!cabecera) return nullopt;
    string_view resto = *cabecera;
    while (!resto.empty()) {
        const auto fin = resto.find(';');
        string_view par = resto.substr(0, fin);
        while (par.starts_with(' ')) par.remove_prefix(1);
        if (par.starts_with("cero_sid=")) return par.substr(9);
        if (fin == string_view::npos) break;
        resto.remove_prefix(fin + 1);
    }
    return nullopt;
}

}
