#include "cero/servidor.hpp"

#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstring>
#include <format>
#include <print>
#include <thread>

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
    std::string salida = std::format("HTTP/1.1 {} {}\r\nContent-Length: {}\r\nContent-Type: {}\r\n",
                                     r.estado, razon(r.estado), r.cuerpo.size(), r.tipo);
    for (const auto& [nombre, valor] : r.extra) salida += std::format("{}: {}\r\n", nombre, valor);
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

}  // namespace

std::optional<std::string_view> Contexto::variable(std::string_view nombre) const {
    const auto i = variables.find(nombre);
    if (i == variables.end()) return std::nullopt;
    return i->second;
}

Servidor&& Servidor::accion(std::string_view nombre,
                            std::function<Respuesta(const Contexto&)> f) && {
    acciones_.emplace(nombre, std::move(f));
    return std::move(*this);
}

Respuesta Servidor::responder(const Peticion& peticion) const {
    const std::string_view camino = peticion.camino();
    if (peticion.destino == "*") {
        return Respuesta::codigo(peticion.metodo == "OPTIONS" ? 200 : 400);
    }

    const auto resolucion = router_.resolver(peticion.metodo, camino);
    // RUT-013: el 405 lleva `Allow`, o quien llama mal sabe que se equivocó pero no en qué.
    if (const auto* mal = std::get_if<VerboNoPermitido>(&resolucion)) {
        std::string lista;
        for (const auto& v : mal->verbos) lista += (lista.empty() ? "" : ", ") + v;
        return Respuesta::codigo(405).cabecera("Allow", lista);
    }
    const auto* hay = std::get_if<Encontrada>(&resolucion);
    if (hay == nullptr) return Respuesta::codigo(404, "no encontrado");  // RUT-012

    const auto accion = acciones_.find(hay->nombre);
    if (accion == acciones_.end()) return Respuesta::codigo(500, "error interno");
    return accion->second(Contexto{peticion, hay->variables});
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
    std::println("cero · escuchando en :{}", puerto);

    while (true) {
        const int cliente = ::accept(oyente, nullptr, nullptr);
        if (cliente < 0) continue;
        // Un hilo del sistema por conexión, igual que en Rust y por el mismo motivo: Java tiene
        // hilos virtuales en la plataforma y aquí no hay equivalente sin traer una dependencia.
        std::thread{[this, cliente] {
            DesdeSocket lector{cliente};
            while (true) {
                const auto peticion = leer(lector);
                if (!peticion) {
                    todo(cliente, escribir(Respuesta::codigo(estado_de(peticion.error())), false, true));
                    break;
                }
                const auto conexion = peticion->cabecera("connection").value_or("");
                const bool cerrar = peticion->version == "HTTP/1.0" || conexion == "close";
                const auto respuesta = responder(*peticion);
                if (!todo(cliente, escribir(respuesta, peticion->metodo == "HEAD", cerrar))) break;
                if (cerrar) break;
            }
            ::close(cliente);
        }}.detach();
    }
}

}  // namespace cero
