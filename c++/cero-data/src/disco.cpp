#include "cero/datos/disco.hpp"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstddef>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
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

constexpr auto kProvisionalViejo = chrono::hours{1};

bool es_identificador(string_view id) {
    return id.size() == 64 && id.find_first_not_of("0123456789abcdef") == string_view::npos;
}

long long en_ms(Reloj::time_point t) {
    return chrono::duration_cast<chrono::milliseconds>(t.time_since_epoch()).count();
}

optional<Reloj::time_point> de_ms(const Json* valor) {
    constexpr auto kMaximo = chrono::duration_cast<chrono::milliseconds>(Reloj::duration::max()).count();
    const auto ms = valor == nullptr ? nullopt : valor->entero();
    if (!ms || *ms < 0 || *ms > kMaximo) return nullopt;
    return Reloj::time_point{chrono::milliseconds{*ms}};
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
        const auto escritos = write(fd, texto.data(), texto.size());
        if (escritos < 0 && errno == EINTR) continue;
        if (escritos <= 0) return false;
        texto.remove_prefix(static_cast<size_t>(escritos));
    }
    return fsync(fd) == 0;
}

void negar(const filesystem::path& carpeta, string_view porque, errc codigo) {
    throw filesystem::filesystem_error(string{porque}, carpeta, make_error_code(codigo));
}

void barrer_provisionales(const filesystem::path& carpeta) {
    const auto limite = filesystem::file_time_type::clock::now() - kProvisionalViejo;
    error_code fallo;
    for (filesystem::directory_iterator i{carpeta, fallo}, fin; !fallo && i != fin; i.increment(fallo)) {
        if (!i->path().filename().string().starts_with('.')) continue;
        error_code otro;
        const auto cuando = filesystem::last_write_time(i->path(), otro);
        if (!otro && cuando < limite) filesystem::remove(i->path(), otro);
    }
}

}

AlmacenEnDisco::AlmacenEnDisco(filesystem::path carpeta, chrono::seconds inactividad,
                               optional<chrono::seconds> vida_maxima)
    : carpeta_(std::move(carpeta)), caducidad_{inactividad, vida_maxima} {
    filesystem::create_directories(carpeta_);
    struct stat datos{};
    if (lstat(carpeta_.c_str(), &datos) != 0) negar(carpeta_, "no se puede leer", errc::io_error);
    if (S_ISLNK(datos.st_mode)) {
        negar(carpeta_, "la carpeta de sesiones es un enlace", errc::too_many_symbolic_link_levels);
    }
    if (!S_ISDIR(datos.st_mode)) negar(carpeta_, "no es una carpeta", errc::not_a_directory);
    if (datos.st_uid != geteuid()) {
        negar(carpeta_, "la carpeta de sesiones es de otro usuario", errc::permission_denied);
    }
    filesystem::permissions(carpeta_, filesystem::perms::owner_all, filesystem::perm_options::replace);
    barrer_provisionales(carpeta_);
}

optional<Guardada> AlmacenEnDisco::recuperar(optional<string_view> id) {
    if (!id || !es_identificador(*id)) return nullopt;
    const lock_guard registro{candado_};
    if (const auto i = vivas_.find(*id); i != vivas_.end()) {
        const Guardada g{i->second.sesion.lock(), i->second.candado.lock()};
        if (g.sesion && g.candado) {
            const auto tomado = g.tomar();
            const auto& s = *g.sesion;
            const bool vencida = caducidad_.vencida(s.creada(), s.tocada());
            if (s.viva() && s.id() == *id && !vencida) return g;
            if (vencida) borrar(*id);
            vivas_.erase(i);
            return nullopt;
        }
        vivas_.erase(i);
    }
    auto g = de_disco(*id);
    if (g) anotar(*id, *g);
    return g;
}

optional<Guardada> AlmacenEnDisco::de_disco(string_view id) const {
    ifstream archivo{carpeta_ / id, ios::binary};
    if (!archivo) return nullopt;
    const string texto{istreambuf_iterator<char>{archivo}, istreambuf_iterator<char>{}};

    const auto leido = leer(texto);
    if (!leido) return nullopt;
    const auto desde = de_ms(leido->get("creada"));
    const auto ultima = de_ms(leido->get("tocada"));
    const auto* datos = leido->get("datos");
    if (!desde || !ultima || datos == nullptr || !datos->como_objeto()) return nullopt;
    if (caducidad_.vencida(*desde, *ultima)) {
        borrar(id);
        return nullopt;
    }

    Atributos atributos;
    for (const auto& [clave, valor] : *datos->como_objeto()) {
        if (const auto v = valor.cadena()) atributos.emplace(clave, *v);
    }
    return Guardada{Sesion::rescatada(id, std::move(atributos), *desde, *ultima),
                    make_shared<mutex>()};
}

void AlmacenEnDisco::anotar(string_view id, const Guardada& g) {
    erase_if(vivas_, [](const auto& par) { return par.second.sesion.expired(); });
    vivas_.insert_or_assign(string{id}, Viva{g.sesion, g.candado});
}

optional<Guardada> AlmacenEnDisco::crear() {
    auto sesion = Sesion::nueva();
    if (!sesion || !escribir(*sesion)) return nullopt;
    (void)sesion->sucia();
    const Guardada g{std::move(sesion), make_shared<mutex>()};
    const lock_guard registro{candado_};
    anotar(g.sesion->id(), g);
    return g;
}

optional<string> AlmacenEnDisco::rotar(const Guardada& g) {
    string viejo;
    optional<string> nuevo;
    {
        const auto tomado = g.tomar();
        viejo = g.sesion->id();
        nuevo = g.sesion->renovar_id();
        if (!nuevo || !escribir(*g.sesion)) return nullopt;
    }
    borrar(viejo);
    const lock_guard registro{candado_};
    vivas_.erase(viejo);
    anotar(*nuevo, g);
    return nuevo;
}

size_t AlmacenEnDisco::cuantas() const {
    size_t n = 0;
    error_code fallo;
    for (filesystem::directory_iterator i{carpeta_, fallo}, fin; !fallo && i != fin; i.increment(fallo)) {
        if (es_identificador(i->path().filename().string())) ++n;
    }
    return n;
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
    const int fd = open(provisional.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    const bool entero = volcar(fd, a_json(s).escribir());
    close(fd);
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
