#include "cero/servidor.hpp"

#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <charconv>
#include <chrono>
#include <cstddef>
#include <cstring>
#include <exception>
#include <expected>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <system_error>
#include <thread>
#include <utility>
#include <variant>
#include <vector>

#include "cero/formato.hpp"
#include "cero/http2/conexion.hpp"
#include "cero/http2/trama.hpp"
#include "cero/socket.hpp"

using namespace std;

namespace cero {
namespace {

string escribir(const Respuesta& r, bool solo_cabeceras, bool cerrar) {
    auto salida = formato("HTTP/1.1 {} {}\r\nContent-Length: {}\r\nContent-Type: {}\r\n", r.estado,
                        razon(r.estado), r.cuerpo.size(), r.tipo);
    for (const auto& [nombre, valor] : r.extra) salida += formato("{}: {}\r\n", nombre, valor);
    salida += cerrar ? "Connection: close\r\n\r\n" : "\r\n";
    if (!solo_cabeceras) salida += r.cuerpo;
    return salida;
}

string desescapar(string_view s) {
    string r;
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '+') {
            r += ' ';
        } else if (s[i] == '%' && i + 2 < s.size()) {
            unsigned valor = 0;
            const auto desde = s.data() + i + 1;
            if (from_chars(desde, desde + 2, valor, 16).ec == errc{}) {
                r += static_cast<char>(valor);
                i += 2;
                continue;
            }
            r += s[i];
        } else {
            r += s[i];
        }
    }
    return r;
}

optional<string> de_pares(string_view cadena, string_view nombre) {
    while (!cadena.empty()) {
        const auto fin = cadena.find('&');
        const auto par = cadena.substr(0, fin);
        if (const auto igual = par.find('='); igual != string_view::npos) {
            if (par.substr(0, igual) == nombre) return desescapar(par.substr(igual + 1));
        }
        if (fin == string_view::npos) break;
        cadena.remove_prefix(fin + 1);
    }
    return nullopt;
}

Fallo en_curso() {
    try {
        throw;
    } catch (const Fallo& f) {
        return f;
    } catch (const exception& e) {
        return Fallo::interno(e.what());
    } catch (...) {
        return Fallo::interno("excepción que no es estándar");
    }
}

}

optional<string_view> Contexto::variable(string_view nombre) const {
    const auto i = variables.find(nombre);
    if (i == variables.end()) return nullopt;
    return i->second;
}

optional<string_view> Contexto::cabecera(string_view nombre) const {
    return peticion.cabecera(nombre);
}

optional<string> Contexto::consulta(string_view nombre) const {
    const auto interrogante = peticion.destino.find('?');
    if (interrogante == string::npos) return nullopt;
    return de_pares(string_view{peticion.destino}.substr(interrogante + 1), nombre);
}

string Contexto::consulta_o(string_view nombre, string_view defecto) const {
    return consulta(nombre).value_or(string{defecto});
}

optional<string> Contexto::campo(string_view nombre) const {
    return de_pares(peticion.cuerpo, nombre);
}

Json Contexto::cuerpo_json() const {
    auto leido = leer(peticion.cuerpo);
    if (!leido) throw Fallo{400, "el cuerpo no es JSON válido: " + leido.error().porque};
    return std::move(*leido);
}

optional<Guardada> Contexto::abrir_sesion() const {
    if (abierta_) return abierta_;
    if (llegada_) return llegada_;
    abierta_ = fuente_.crear();
    return abierta_;
}

optional<string> Contexto::token_csrf() const {
    const auto g = abrir_sesion();
    if (!g) return nullopt;
    const auto tomado = g->tomar();
    if (const auto ya = g->sesion->leer(kClaveCsrf)) return string{*ya};
    const auto nuevo = identificador();
    if (!nuevo) return nullopt;
    g->sesion->poner(kClaveCsrf, *nuevo);
    return nuevo;
}

string Servidor::anotar(string_view metodo, string_view patron) {
    auto nombre = formato("{} {}", metodo, patron);
    if (auto hecho = router_.ruta(metodo, patron, nombre); !hecho) {
        mal_formadas_.push_back(formato("{}: {}", nombre, hecho.error()));
    }
    return nombre;
}

Servidor&& Servidor::usar(Medio m) && {
    medios_.push_back(std::move(m));
    return std::move(*this);
}

Servidor&& Servidor::al_fallar(AlFallar f) && {
    al_fallar_ = std::move(f);
    return std::move(*this);
}

Servidor&& Servidor::por_defecto(Accion f) && {
    por_defecto_ = std::move(f);
    return std::move(*this);
}

Servidor&& Servidor::proteccion(Proteccion p) && {
    proteccion_ = std::move(p);
    return std::move(*this);
}

Servidor&& Servidor::cors(Cors c) && {
    cors_ = std::move(c);
    return std::move(*this);
}

Servidor&& Servidor::limite(unsigned cupo, chrono::seconds ventana) && {
    limitador_ = make_shared<Limitador>(cupo, ventana);
    return std::move(*this);
}

Servidor&& Servidor::csrf(Textos exenciones) && {
    csrf_activo_ = true;
    csrf_exento_ = std::move(exenciones);
    return std::move(*this);
}

Servidor&& Servidor::salud(shared_ptr<Salud> s) && {
    salud_ = std::move(s);
    return std::move(*this);
}

Servidor&& Servidor::sesiones(shared_ptr<Sesiones> s) && {
    sesiones_ = std::move(s);
    return std::move(*this);
}

Servidor&& Servidor::sin_registrar(Textos caminos) && {
    sin_registrar_ = std::move(caminos);
    for (const auto& c : sin_registrar_) metricas_->ignorar(c);
    return std::move(*this);
}

Servidor&& Servidor::plazos(Plazos p) && {
    plazos_ = p;
    return std::move(*this);
}

Servidor&& Servidor::limites(Limites l) && {
    limites_ = l;
    return std::move(*this);
}

Servidor&& Servidor::direccion(string d) && {
    direccion_ = std::move(d);
    return std::move(*this);
}

Servidor&& Servidor::tras_tls() && {
    seguro_ = true;
    return std::move(*this);
}

Respuesta Servidor::interno(const Json& cuerpo) const {
    auto r = Respuesta::json(cuerpo.escribir());
    r.estado = 500;
    const auto proteccion = proteccion_.aplicar(seguro_);
    r.extra.insert(r.extra.end(), proteccion.begin(), proteccion.end());
    return r;
}

Respuesta Servidor::responder(const Peticion& p, string_view cliente) const {
    if (!mal_formadas_.empty()) {
        const Json::Lista rutas{mal_formadas_.begin(), mal_formadas_.end()};
        return interno(Json::objeto({{"error", "rutas mal formadas"}, {"rutas", rutas}}));
    }
    try {
        return despachar(p, cliente);
    } catch (...) {
        const auto camino = p.camino();
        log_->escribir(Nivel::Error, "{} {} falló fuera de la acción: {}",
                       {p.metodo, camino, en_curso().mensaje});
        return interno(Json::objeto({{"error", "error interno"}, {"ruta", camino}}));
    }
}

Respuesta Servidor::despachar(const Peticion& p, string_view cliente) const {
    const auto empezo = chrono::steady_clock::now();
    const string camino{p.camino()};

    if (salud_) {
        const auto informe = camino == "/cero/vivo"  ? optional{salud_->vivo()}
                             : camino == "/cero/listo" ? optional{salud_->listo()}
                                                       : nullopt;
        if (informe) {
            Respuesta r = Respuesta::json(informe->cuerpo);
            r.estado = informe->estado;
            return rematar(std::move(r), p, camino, empezo, nullopt);
        }
    }

    if (limitador_) {
        const auto v = limitador_->pedir(cliente);
        auto cabeceras = cabeceras_limite(v);
        if (!v.permitida) {
            auto r = Respuesta::codigo(429, "demasiadas peticiones");
            r.extra.insert(r.extra.end(), cabeceras.begin(), cabeceras.end());
            return rematar(std::move(r), p, camino, empezo, nullopt);
        }
    }

    Pares de_cors;
    if (cors_) {
        const auto decision = cors_->decidir(p.metodo, p.cabecera("origin"));
        if (const auto* corta = get_if<Cors::Corta>(&decision)) {
            auto r = Respuesta::codigo(corta->estado);
            r.extra = corta->cabeceras;
            return rematar(std::move(r), p, camino, empezo, nullopt);
        }
        de_cors = std::get<Cors::Sigue>(decision).cabeceras;
    }

    auto llegada = sesiones_->recuperar(id_de_cookie(p.cabecera("cookie")));

    const auto resolucion = router_.resolver(p.metodo, camino);
    const auto* hay = get_if<Encontrada>(&resolucion);
    static const Captura kSinVariables;
    const Contexto ctx{p, hay != nullptr ? hay->variables : kSinVariables, std::move(llegada),
                       *sesiones_};

    Respuesta r;
    try {
        r = cadena(0, ctx, camino, resolucion);
    } catch (...) {
        r = del_fallo(ctx, patron(p, camino), en_curso());
    }
    r.extra.insert(r.extra.end(), de_cors.begin(), de_cors.end());
    return rematar(std::move(r), p, camino, empezo, ctx.final());
}

Respuesta Servidor::cadena(size_t i, const Contexto& ctx, string_view camino,
                           const Resolucion& resolucion) const {
    if (i == medios_.size()) return atender_ruta(ctx, camino, resolucion);
    return medios_[i](ctx, [this, i, camino, &resolucion](const Contexto& c) {
        return cadena(i + 1, c, camino, resolucion);
    });
}

Respuesta Servidor::atender_ruta(const Contexto& ctx, string_view camino,
                                 const Resolucion& resolucion) const {
    const auto& p = ctx.peticion;
    if (p.destino == "*") {
        return Respuesta::codigo(p.metodo == "OPTIONS" ? 200 : 400);
    }

    if (const auto* mal = get_if<VerboNoPermitido>(&resolucion)) {
        string lista;
        for (const auto& v : mal->verbos) lista += (lista.empty() ? "" : ", ") + v;
        return Respuesta::codigo(405).cabecera("Allow", lista);
    }
    const auto* hay = get_if<Encontrada>(&resolucion);
    if (hay == nullptr) {
        return por_defecto_ ? por_defecto_(ctx) : Respuesta::codigo(404, "no encontrado");
    }

    if (csrf_activo_) {
        optional<string> en_sesion;
        if (const auto& g = ctx.sesion()) {
            const auto tomado = g->tomar();
            if (const auto t = g->sesion->leer(kClaveCsrf)) en_sesion = string{*t};
        }
        auto presentado = ctx.cabecera("x-csrf-token").transform([](auto v) { return string{v}; });
        if (!presentado) presentado = ctx.campo(kCampoCsrf);
        if (!presentado) presentado = ctx.consulta(kCampoCsrf);

        const auto vista = [](const optional<string>& o) {
            return o ? optional<string_view>{*o} : nullopt;
        };
        if (!csrf_valido(p.metodo, camino, csrf_exento_, vista(en_sesion), vista(presentado))) {
            return Respuesta::codigo(403, "token CSRF ausente o inválido");
        }
    }

    return actuar(ctx, hay->nombre, patron(p, camino));
}

Respuesta Servidor::actuar(const Contexto& ctx, string_view nombre, string_view ruta) const {
    const auto accion = acciones_.find(nombre);
    if (accion == acciones_.end()) {
        const auto huerfana = formato("la ruta {} no tiene acción registrada", nombre);
        return del_fallo(ctx, ruta, Fallo::interno(huerfana));
    }
    try {
        return accion->second(ctx);
    } catch (...) {
        return del_fallo(ctx, ruta, en_curso());
    }
}

Respuesta Servidor::del_fallo(const Contexto& ctx, string_view ruta, const Fallo& fallo) const {
    if (al_fallar_) {
        try {
            return al_fallar_(ctx, fallo);
        } catch (...) {
            log_->escribir(Nivel::Error, "el manejador de fallos de {} también falló: {} · el fallo era: {}",
                           {ruta, en_curso().mensaje, fallo.mensaje});
        }
    }
    if (fallo.contable()) {
        if (!fallo.detalle) return Respuesta::codigo(fallo.estado, fallo.mensaje);
        auto r = Respuesta::json(fallo.detalle->escribir());
        r.estado = fallo.estado;
        return r;
    }
    const auto detalle = fallo.detalle ? fallo.detalle->escribir() : string{};
    log_->escribir(Nivel::Error, "{} falló: {} {}", {ruta, fallo.mensaje, detalle});
    auto r = Respuesta::json(Json::objeto({{"error", "error interno"}, {"ruta", ruta}}).escribir());
    r.estado = fallo.estado;
    return r;
}

string Servidor::patron(const Peticion& p, string_view camino) const {
    return router_.patron_de(p.metodo, camino).value_or(string{camino});
}

Respuesta Servidor::rematar(Respuesta r, const Peticion& p, string_view camino,
                            chrono::steady_clock::time_point empezo,
                            const optional<Guardada>& sesion) const {
    const auto proteccion = proteccion_.aplicar(seguro_);
    r.extra.insert(r.extra.end(), proteccion.begin(), proteccion.end());

    if (sesion) {
        {
            const auto tomado = sesion->tomar();
            if (const auto id = sesion->sesion->cookie_pendiente()) {
                r.extra.emplace_back("Set-Cookie", cabecera_cookie(*id, seguro_));
            }
        }
        sesiones_->guardar(*sesion);
    }

    const auto tardo = chrono::duration_cast<chrono::microseconds>(
        chrono::steady_clock::now() - empezo);
    metricas_->anotar(patron(p, camino), r.estado, tardo);
    if (ranges::find(sin_registrar_, camino) == sin_registrar_.end()) {
        log_->escribir(Nivel::Info, "{}",
                       {linea_acceso(p.metodo, p.destino, r.estado, nullopt, tardo)});
    }
    return r;
}

namespace {

int abrir_oyente(const string& direccion, unsigned short puerto) {
    addrinfo pista{};
    pista.ai_family = AF_UNSPEC;
    pista.ai_socktype = SOCK_STREAM;
    pista.ai_flags = AI_PASSIVE;
    addrinfo* lista = nullptr;
    const auto servicio = to_string(puerto);
    if (const int fallo = getaddrinfo(direccion.c_str(), servicio.c_str(), &pista, &lista);
        fallo != 0) {
        aviso("cero · dirección no válida: {} ({})", direccion, gai_strerror(fallo));
        return -1;
    }
    int oyente = -1;
    int error = 0;
    for (const addrinfo* a = lista; a != nullptr; a = a->ai_next) {
        oyente = socket(a->ai_family, a->ai_socktype, a->ai_protocol);
        if (oyente < 0) {
            error = errno;
            continue;
        }
        const int si = 1;
        setsockopt(oyente, SOL_SOCKET, SO_REUSEADDR, &si, sizeof si);
        if (enlazar(oyente, a->ai_addr, a->ai_addrlen) == 0 && listen(oyente, 128) == 0) break;
        error = errno;
        close(oyente);
        oyente = -1;
    }
    freeaddrinfo(lista);
    if (oyente < 0) {
        aviso("cero · no se puede escuchar en {}:{}: {}", direccion, puerto, strerror(error));
    }
    return oyente;
}

string quien_es(const sockaddr_storage& quien, socklen_t cuanto) {
    char texto[NI_MAXHOST] = {};
    if (getnameinfo(reinterpret_cast<const sockaddr*>(&quien), cuanto, texto, sizeof texto,
                    nullptr, 0, NI_NUMERICHOST) != 0) {
        return "desconocido";
    }
    return texto;
}

}

void Servidor::conversar(int cliente, const string& desde, Plazos plazos, Limites limites) const {
    plazo_de_lectura(cliente, plazos.inactividad);
    http2::DesdeSocket lector{cliente};
    const http2::Atender atender = [this, &desde](const Peticion& p) { return responder(p, desde); };
    if (lector.asomar(http2::kPreambulo)) {
        http2::servir(lector, http2::Ajustes{}, atender, nullopt, plazos, limites);
        return;
    }
    while (lector.esperar(plazos.inactividad)) {
        auto peticion = leer(lector, limites.cuerpo);
        if (!peticion) {
            const auto fallo = Respuesta::codigo(estado_de(peticion.error()));
            mandar_todo(cliente, escribir(fallo, false, true), plazos);
            return;
        }
        if (http2::pide_h2c(*peticion)) {
            http2::servir(lector, http2::Ajustes{}, atender, std::move(*peticion), plazos, limites);
            return;
        }
        const auto conexion = peticion->cabecera("connection").value_or("");
        const bool cerrar = peticion->version == "HTTP/1.0" || conexion == "close";
        const auto respuesta = responder(*peticion, desde);
        const bool cabeza = peticion->metodo == "HEAD";
        if (!mandar_todo(cliente, escribir(respuesta, cabeza, cerrar), plazos) || cerrar) return;
    }
}

int Servidor::escuchar(unsigned short puerto) const {
    if (!mal_formadas_.empty()) {
        for (const auto& m : mal_formadas_) aviso("cero · ruta mal formada, {}", m);
        return 1;
    }
    if (!plazos_.validos() || !limites_.validos()) {
        aviso("cero · los plazos y los topes tienen que ser mayores que cero");
        return 1;
    }
    const int oyente = abrir_oyente(direccion_, puerto);
    if (oyente < 0) return 1;
    linea("cero · escuchando en :{}", puerto);

    const auto vivas = make_shared<atomic<size_t>>(0);
    while (true) {
        sockaddr_storage quien{};
        auto cuanto = static_cast<socklen_t>(sizeof quien);
        const int cliente = accept(oyente, reinterpret_cast<sockaddr*>(&quien), &cuanto);
        if (cliente < 0) continue;
        const auto desde = quien_es(quien, cuanto);
        sin_sigpipe(cliente);
        try {
            if (++*vivas > limites_.conexiones) throw system_error{make_error_code(errc::resource_unavailable_try_again)};
            thread{[this, cliente, desde, vivas, plazos = plazos_, limites = limites_] {
                try {
                    conversar(cliente, desde, plazos, limites);
                } catch (...) {
                    log_->escribir(Nivel::Error, "la conexión con {} cayó: {}", {desde, en_curso().mensaje});
                }
                close(cliente);
                --*vivas;
            }}.detach();
        } catch (const system_error&) {
            --*vivas;
            close(cliente);
        }
    }
}

}
