#include "cero/servidor.hpp"

#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <charconv>
#include <thread>

#include "cero/texto.hpp"

// Aquí está la decisión de fondo de esta implementación: **C++ no tiene sockets**. Java los trae
// en el JDK y Rust en `std::net`; el estándar de C++ no trae ninguno, porque el Networking TS se
// abandonó. Las dos salidas eran traer asio —una dependencia, y este proyecto no admite ninguna— o
// llamar a POSIX. Se llama a POSIX, igual que en Rust se usa un hilo del sistema en vez de un
// runtime asíncrono: el contrato no habla de cómo se abre el socket, así que se cumple igual.

namespace cero {
namespace {

// Los octetos del socket, de uno en uno sobre un búfer propio. Un búfer nuevo por petición tira lo
// que ya tenga dentro, y lo que tenga dentro puede ser la petición siguiente de un cliente que las
// encadena: es un fallo que no se ve con un navegador y sí con `curl` mandando dos de una vez.
class DesdeSocket final : public Lector {
public:
    explicit DesdeSocket(int descriptor) : descriptor_(descriptor) {}

    int octeto() override {
        if (i_ == tengo_) {
            const auto leidos = ::read(descriptor_, buf_.data(), buf_.size());
            if (leidos <= 0) return -1;
            tengo_ = static_cast<std::size_t>(leidos);
            i_ = 0;
        }
        return static_cast<unsigned char>(buf_[i_++]);
    }

private:
    int descriptor_;
    std::array<char, 8192> buf_{};
    std::size_t i_ = 0;
    std::size_t tengo_ = 0;
};

std::string escribir(const Respuesta& r, bool solo_cabeceras, bool cerrar) {
    auto salida = texto("HTTP/1.1 {} {}\r\nContent-Length: {}\r\nContent-Type: {}\r\n", r.estado,
                        razon(r.estado), r.cuerpo.size(), r.tipo);
    for (const auto& [nombre, valor] : r.extra) salida += texto("{}: {}\r\n", nombre, valor);
    salida += cerrar ? "Connection: close\r\n\r\n" : "\r\n";
    if (!solo_cabeceras) salida += r.cuerpo;
    return salida;
}

bool todo(int descriptor, std::string_view datos) {
    while (!datos.empty()) {
        const auto escritos = ::write(descriptor, datos.data(), datos.size());
        if (escritos <= 0) return false;
        datos.remove_prefix(static_cast<std::size_t>(escritos));
    }
    return true;
}

std::string desescapar(std::string_view s) {
    std::string r;
    for (std::size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '+') {
            r += ' ';
        } else if (s[i] == '%' && i + 2 < s.size()) {
            unsigned valor = 0;
            const auto desde = s.data() + i + 1;
            if (std::from_chars(desde, desde + 2, valor, 16).ec == std::errc{}) {
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

// Parte `a=1&b=hola+mundo`. Un valor mal codificado no aborta la lectura entera: se conserva tal
// cual, porque tirar el formulario completo por un campo roto es peor que quedarse con el campo.
std::optional<std::string> de_pares(std::string_view cadena, std::string_view nombre) {
    while (!cadena.empty()) {
        const auto fin = cadena.find('&');
        const auto par = cadena.substr(0, fin);
        if (const auto igual = par.find('='); igual != std::string_view::npos) {
            if (par.substr(0, igual) == nombre) return desescapar(par.substr(igual + 1));
        }
        if (fin == std::string_view::npos) break;
        cadena.remove_prefix(fin + 1);
    }
    return std::nullopt;
}

}  // namespace

std::optional<std::string_view> Contexto::variable(std::string_view nombre) const {
    const auto i = variables.find(nombre);
    if (i == variables.end()) return std::nullopt;
    return i->second;
}

std::optional<std::string_view> Contexto::cabecera(std::string_view nombre) const {
    return peticion.cabecera(nombre);
}

std::optional<std::string> Contexto::consulta(std::string_view nombre) const {
    const auto interrogante = peticion.destino.find('?');
    if (interrogante == std::string::npos) return std::nullopt;
    return de_pares(std::string_view{peticion.destino}.substr(interrogante + 1), nombre);
}

// RUT-016: un defecto de cadena vacía no es lo mismo que «sin defecto». Para una búsqueda,
// ausente puede querer decir «todo» y vacío «nada», y confundirlos cambia el resultado.
std::string Contexto::consulta_o(std::string_view nombre, std::string_view defecto) const {
    return consulta(nombre).value_or(std::string{defecto});
}

std::optional<std::string> Contexto::campo(std::string_view nombre) const {
    return de_pares(peticion.cuerpo, nombre);
}

std::optional<Guardada> Contexto::abrir_sesion() const {
    if (abierta_) return abierta_;
    if (llegada_) return llegada_;
    abierta_ = fuente_.crear();
    return abierta_;
}

std::optional<std::string> Contexto::token_csrf() const {
    const auto g = abrir_sesion();
    if (!g) return std::nullopt;
    const auto tomado = g->tomar();
    if (const auto ya = g->sesion->leer(kClaveCsrf)) return std::string{*ya};
    const auto nuevo = identificador();
    if (!nuevo) return std::nullopt;
    g->sesion->poner(kClaveCsrf, *nuevo);
    return nuevo;
}

Servidor&& Servidor::accion(std::string_view nombre,
                            std::function<Respuesta(const Contexto&)> f) && {
    acciones_.emplace(nombre, std::move(f));
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

Servidor&& Servidor::limite(unsigned cupo, std::chrono::seconds ventana) && {
    limitador_ = std::make_shared<Limitador>(cupo, ventana);
    return std::move(*this);
}

Servidor&& Servidor::csrf(std::vector<std::string> exenciones) && {
    csrf_activo_ = true;
    csrf_exento_ = std::move(exenciones);
    return std::move(*this);
}

Servidor&& Servidor::salud(std::shared_ptr<Salud> s) && {
    salud_ = std::move(s);
    return std::move(*this);
}

// SES-012: dos servidores con el **mismo** almacén reconocen las mismas sesiones. Es lo que separa
// poder poner una segunda instancia detrás del balanceador de no poder.
Servidor&& Servidor::sesiones(std::shared_ptr<Sesiones> s) && {
    sesiones_ = std::move(s);
    return std::move(*this);
}

// OBS-017 y OBS-023: el caso que lo pide es la sonda de salud, que en un orquestador entra cada
// pocos segundos y ahoga todo lo demás. Se declara por camino y no se adivina.
Servidor&& Servidor::sin_registrar(std::vector<std::string> caminos) && {
    sin_registrar_ = std::move(caminos);
    for (const auto& c : sin_registrar_) metricas_->ignorar(c);
    return std::move(*this);
}

Servidor&& Servidor::tras_tls() && {
    seguro_ = true;
    return std::move(*this);
}

Respuesta Servidor::responder(const Peticion& p, std::string_view cliente) const {
    const auto empezo = std::chrono::steady_clock::now();
    const std::string camino{p.camino()};

    // 1 · salud, antes que nada: un proceso que no puede atender tiene que poder decirlo.
    if (salud_) {
        const auto informe = camino == "/cero/vivo"  ? std::optional{salud_->vivo()}
                             : camino == "/cero/listo" ? std::optional{salud_->listo()}
                                                       : std::nullopt;
        if (informe) {
            Respuesta r = Respuesta::json(informe->cuerpo);
            r.estado = informe->estado;
            return rematar(std::move(r), p, camino, empezo, std::nullopt);
        }
    }

    // 2 · límite de peticiones.
    if (limitador_) {
        const auto v = limitador_->pedir(cliente);
        auto cabeceras = cabeceras_limite(v);
        if (!v.permitida) {
            auto r = Respuesta::codigo(429, "demasiadas peticiones");
            r.extra.insert(r.extra.end(), cabeceras.begin(), cabeceras.end());
            return rematar(std::move(r), p, camino, empezo, std::nullopt);
        }
    }

    // 3 · CORS, que puede cortar en seco un preflight ajeno.
    Pares de_cors;
    if (cors_) {
        const auto decision = cors_->decidir(p.metodo, p.cabecera("origin"));
        if (const auto* corta = std::get_if<Cors::Corta>(&decision)) {
            auto r = Respuesta::codigo(corta->estado);
            r.extra = corta->cabeceras;
            return rematar(std::move(r), p, camino, empezo, std::nullopt);
        }
        de_cors = std::get<Cors::Sigue>(decision).cabeceras;
    }

    // 4 · sesión: se recupera, nunca se crea.
    auto llegada = sesiones_->recuperar(id_de_cookie(p.cabecera("cookie")));

    const auto resolucion = router_.resolver(p.metodo, camino);
    const auto* hay = std::get_if<Encontrada>(&resolucion);
    static const Captura kSinVariables;
    const Contexto ctx{p, hay != nullptr ? hay->variables : kSinVariables, std::move(llegada),
                       *sesiones_};

    auto r = atender_ruta(ctx, camino);
    r.extra.insert(r.extra.end(), de_cors.begin(), de_cors.end());
    return rematar(std::move(r), p, camino, empezo, ctx.final());
}

// El ruteo va **antes** que el CSRF. Un verbo no admitido tiene que dar 405 y un camino
// inexistente 404: si el CSRF responde 403 primero, la respuesta atribuye el fallo a la causa
// equivocada, que es lo que RUT-009 prohíbe al exigir distinguir 404 de 405. Se descubrió montando
// el framework entero en Rust — con los módulos sueltos no se veía— y aquí está desde el principio.
Respuesta Servidor::atender_ruta(const Contexto& ctx, std::string_view camino) const {
    const auto& p = ctx.peticion;
    if (p.destino == "*") {
        return Respuesta::codigo(p.metodo == "OPTIONS" ? 200 : 400);
    }

    const auto resolucion = router_.resolver(p.metodo, camino);
    // RUT-013: el 405 lleva `Allow`, o quien llama mal sabe que se equivocó pero no en qué.
    if (const auto* mal = std::get_if<VerboNoPermitido>(&resolucion)) {
        std::string lista;
        for (const auto& v : mal->verbos) lista += (lista.empty() ? "" : ", ") + v;
        return Respuesta::codigo(405).cabecera("Allow", lista);
    }
    const auto* hay = std::get_if<Encontrada>(&resolucion);
    if (hay == nullptr) return Respuesta::codigo(404, "no encontrado");  // RUT-012

    // SEG-015 a SEG-019, ya sabiendo que la petición iba a alguna parte.
    if (csrf_activo_) {
        std::optional<std::string> en_sesion;
        if (const auto& g = ctx.sesion()) {
            const auto tomado = g->tomar();
            if (const auto t = g->sesion->leer(kClaveCsrf)) en_sesion = std::string{*t};
        }
        // Por cabecera, por campo del formulario o por la consulta. Solo la cabecera dejaba fuera
        // a un formulario HTML, que es justo donde el CSRF hace más falta.
        auto presentado = ctx.cabecera("x-csrf-token").transform([](auto v) { return std::string{v}; });
        if (!presentado) presentado = ctx.campo(kCampoCsrf);
        if (!presentado) presentado = ctx.consulta(kCampoCsrf);

        const auto vista = [](const std::optional<std::string>& o) {
            return o ? std::optional<std::string_view>{*o} : std::nullopt;
        };
        if (!csrf_valido(p.metodo, camino, csrf_exento_, vista(en_sesion), vista(presentado))) {
            return Respuesta::codigo(403, "token CSRF ausente o inválido");
        }
    }

    const auto accion = acciones_.find(hay->nombre);
    if (accion == acciones_.end()) {
        // RUT-024: el detalle interno no se filtra al cliente; va al log.
        log_->escribir(Nivel::Error, "la ruta {} no tiene acción registrada", {hay->nombre});
        return Respuesta::codigo(500, "error interno");
    }
    return accion->second(ctx);
}

// Lo que se aplica a **toda** respuesta salga por donde salga. Que sea un solo sitio no es estilo:
// SES-010 nació de tener dos salidas y poner la cookie en una.
Respuesta Servidor::rematar(Respuesta r, const Peticion& p, std::string_view camino,
                            std::chrono::steady_clock::time_point empezo,
                            const std::optional<Guardada>& sesion) const {
    const auto proteccion = proteccion_.aplicar(seguro_);
    r.extra.insert(r.extra.end(), proteccion.begin(), proteccion.end());

    if (sesion) {
        {
            const auto tomado = sesion->tomar();
            // SES-008 y SES-011: se consulta una sola vez, aquí, y consultarla la consume.
            if (const auto id = sesion->sesion->cookie_pendiente()) {
                r.extra.emplace_back("Set-Cookie", cabecera_cookie(*id, seguro_));
            }
        }
        sesiones_->guardar(*sesion);
    }

    const auto tardo = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - empezo);
    const auto patron = router_.patron_de(p.metodo, camino).value_or(std::string{camino});
    metricas_->anotar(patron, r.estado, tardo);
    // OBS-012: el estado que se registra es el que **sale**. Anotarlo antes de que la acción pueda
    // fallar deja un log lleno de doscientos que el cliente recibió como quinientos, y entonces el
    // log dice justo lo contrario de lo que pasó.
    if (std::ranges::find(sin_registrar_, camino) == sin_registrar_.end()) {
        log_->escribir(Nivel::Info, "{}",
                       {linea_acceso(p.metodo, p.destino, r.estado, std::nullopt, tardo)});
    }
    return r;
}

int Servidor::escuchar(unsigned short puerto) const {
    const int oyente = ::socket(AF_INET, SOCK_STREAM, 0);
    if (oyente < 0) return 1;
    const int si = 1;
    ::setsockopt(oyente, SOL_SOCKET, SO_REUSEADDR, &si, sizeof si);

    sockaddr_in donde{};
    donde.sin_family = AF_INET;
    donde.sin_addr.s_addr = INADDR_ANY;
    donde.sin_port = htons(puerto);
    if (::bind(oyente, reinterpret_cast<sockaddr*>(&donde), sizeof donde) < 0) return 1;
    if (::listen(oyente, 128) < 0) return 1;
    linea("cero · escuchando en :{}", puerto);

    while (true) {
        sockaddr_in quien{};
        auto cuanto = static_cast<socklen_t>(sizeof quien);
        const int cliente = ::accept(oyente, reinterpret_cast<sockaddr*>(&quien), &cuanto);
        if (cliente < 0) continue;
        const auto desde = texto("{}.{}.{}.{}", quien.sin_addr.s_addr & 0xff,
                                 (quien.sin_addr.s_addr >> 8) & 0xff,
                                 (quien.sin_addr.s_addr >> 16) & 0xff,
                                 (quien.sin_addr.s_addr >> 24) & 0xff);
        // Un hilo del sistema por conexión, igual que en Rust y por el mismo motivo: Java tiene
        // hilos virtuales en la plataforma y aquí no hay equivalente sin traer una dependencia.
        std::thread{[this, cliente, desde] {
            DesdeSocket lector{cliente};
            while (true) {
                const auto peticion = leer(lector);
                if (!peticion) {
                    todo(cliente,
                         escribir(Respuesta::codigo(estado_de(peticion.error())), false, true));
                    break;
                }
                const auto conexion = peticion->cabecera("connection").value_or("");
                const bool cerrar = peticion->version == "HTTP/1.0" || conexion == "close";
                const auto respuesta = responder(*peticion, desde);
                if (!todo(cliente, escribir(respuesta, peticion->metodo == "HEAD", cerrar))) break;
                if (cerrar) break;
            }
            ::close(cliente);
        }}.detach();
    }
}

}  // namespace cero
