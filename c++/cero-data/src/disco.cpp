#include "cero/datos/disco.hpp"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <system_error>
#include <utility>

#include "cero/json.hpp"

using namespace std;

namespace cero {
namespace {

bool es_identificador(string_view id) {
    return id.size() == 64 && id.find_first_not_of("0123456789abcdef") == string_view::npos;
}

long long en_ms(Reloj::time_point t) {
    return chrono::duration_cast<chrono::milliseconds>(t.time_since_epoch()).count();
}

Json a_json(const Sesion& s) {
    Json::Objeto datos;
    for (const auto& [clave, valor] : s.atributos()) datos.emplace(clave, valor);
    return Json::objeto({{"creada", en_ms(s.creada())},
                         {"tocada", en_ms(s.tocada())},
                         {"datos", std::move(datos)}});
}

bool volcar(int fd, string_view texto) {
    while (!texto.empty()) {
        const auto escritos = ::write(fd, texto.data(), texto.size());
        if (escritos <= 0) return false;
        texto.remove_prefix(static_cast<size_t>(escritos));
    }
    return true;
}

}

AlmacenEnDisco::AlmacenEnDisco(filesystem::path carpeta, chrono::seconds inactividad,
                               optional<chrono::seconds> vida_maxima)
    : carpeta_(std::move(carpeta)), caducidad_{inactividad, vida_maxima} {
    filesystem::create_directories(carpeta_);
    filesystem::permissions(carpeta_, filesystem::perms::owner_all, filesystem::perm_options::replace);
}

optional<Guardada> AlmacenEnDisco::recuperar(optional<string_view> id) {
    if (!id || !es_identificador(*id)) return nullopt;
    ifstream archivo{carpeta_ / *id, ios::binary};
    if (!archivo) return nullopt;
    const string texto{istreambuf_iterator<char>{archivo}, istreambuf_iterator<char>{}};

    const auto leido = leer(texto);
    const auto* creada = leido ? leido->get("creada") : nullptr;
    const auto* tocada = leido ? leido->get("tocada") : nullptr;
    const auto* datos = leido ? leido->get("datos") : nullptr;
    if (!creada || !tocada || !datos || !creada->entero() || !tocada->entero() ||
        !datos->como_objeto()) {
        return nullopt;
    }
    const Reloj::time_point desde{chrono::milliseconds{*creada->entero()}};
    const Reloj::time_point ultima{chrono::milliseconds{*tocada->entero()}};
    if (caducidad_.vencida(desde, ultima)) {
        borrar(*id);
        return nullopt;
    }

    Atributos atributos;
    for (const auto& [clave, valor] : *datos->como_objeto()) {
        if (const auto v = valor.cadena()) atributos.emplace(clave, *v);
    }
    return Guardada{Sesion::rescatada(*id, std::move(atributos), desde, ultima),
                    make_shared<mutex>()};
}

optional<Guardada> AlmacenEnDisco::crear() {
    auto sesion = Sesion::nueva();
    if (!sesion || !escribir(*sesion)) return nullopt;
    (void)sesion->sucia();
    return Guardada{std::move(sesion), make_shared<mutex>()};
}

optional<string> AlmacenEnDisco::rotar(const Guardada& g) {
    const auto tomado = g.tomar();
    const auto viejo = g.sesion->id();
    const auto nuevo = g.sesion->renovar_id();
    if (!nuevo || !escribir(*g.sesion)) return nullopt;
    borrar(viejo);
    return nuevo;
}

size_t AlmacenEnDisco::cuantas() const {
    error_code fallo;
    return static_cast<size_t>(ranges::count_if(
        filesystem::directory_iterator{carpeta_, fallo}, [](const filesystem::directory_entry& e) {
            return es_identificador(e.path().filename().string());
        }));
}

void AlmacenEnDisco::guardar(const Guardada& g) {
    const auto tomado = g.tomar();
    if (!g.sesion->sucia()) return;
    if (g.sesion->viva()) {
        (void)escribir(*g.sesion);
    } else {
        borrar(g.sesion->id());
    }
}

bool AlmacenEnDisco::escribir(const Sesion& s) const {
    const auto sufijo = identificador();
    if (!sufijo) return false;
    const auto provisional = carpeta_ / ("." + s.id() + "." + sufijo->substr(0, 16));
    const int fd = ::open(provisional.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    const bool entero = volcar(fd, a_json(s).escribir());
    ::close(fd);
    error_code fallo;
    if (entero) filesystem::rename(provisional, carpeta_ / s.id(), fallo);
    const bool hecho = entero && !fallo;
    if (!hecho) filesystem::remove(provisional, fallo);
    return hecho;
}

void AlmacenEnDisco::borrar(string_view id) const {
    error_code fallo;
    filesystem::remove(carpeta_ / id, fallo);
}

}
