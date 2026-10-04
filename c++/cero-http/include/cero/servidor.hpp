#pragma once

#include <chrono>
#include <expected>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "cero/json.hpp"
#include "cero/observabilidad.hpp"
#include "cero/peticion.hpp"
#include "cero/respuesta.hpp"
#include "cero/ruta.hpp"
#include "cero/seguridad.hpp"
#include "cero/sesion.hpp"

namespace cero {

class Servidor;

// Lo que una acción recibe. El contrato no dice cómo se llama nada de esto: solo qué tiene que
// estar disponible y qué forma toma la respuesta.
class Contexto {
public:
    const Peticion& peticion;
    const Captura& variables;

    std::optional<std::string_view> variable(std::string_view nombre) const;
    std::optional<std::string_view> cabecera(std::string_view nombre) const;
    std::optional<std::string> consulta(std::string_view nombre) const;
    std::string consulta_o(std::string_view nombre, std::string_view defecto) const;
    std::optional<std::string> campo(std::string_view nombre) const;
    // RUT-017: el cuerpo interpretado como JSON. Un cuerpo mal formado es 400 y no 500: lo mandó
    // mal el cliente, no falló el servidor.
    std::expected<Json, FalloJson> cuerpo_json() const;

    // SES-001: la de esta petición si llegó alguna. Leer no crea.
    const std::optional<Guardada>& sesion() const { return llegada_; }
    // Solo esto la crea, y solo cuando la aplicación lo pide. Llamarlo dos veces devuelve la
    // misma: si no fuera idempotente, la segunda llamada dejaría huérfana a la primera.
    std::optional<Guardada> abrir_sesion() const;
    // SEG-017: el token de esta petición, creándolo si aún no lo hay. **Abre sesión**, porque un
    // token que no esté atado a una sesión no protege de nada: lo pediría el sitio atacante.
    std::optional<std::string> token_csrf() const;

private:
    friend class Servidor;
    Contexto(const Peticion& p, const Captura& v, std::optional<Guardada> llegada, Sesiones& fuente)
        : peticion(p), variables(v), llegada_(std::move(llegada)), fuente_(fuente) {}

    std::optional<Guardada> final() const { return abierta_ ? abierta_ : llegada_; }

    std::optional<Guardada> llegada_;
    mutable std::optional<Guardada> abierta_;
    Sesiones& fuente_;
};

class Servidor {
public:
    explicit Servidor(Router router) : router_(std::move(router)) {}

    Servidor&& accion(std::string_view nombre, std::function<Respuesta(const Contexto&)> f) &&;
    Servidor&& proteccion(Proteccion p) &&;
    Servidor&& cors(Cors c) &&;
    Servidor&& limite(unsigned cupo, std::chrono::seconds ventana) &&;
    Servidor&& csrf(std::vector<std::string> exenciones) &&;
    Servidor&& salud(std::shared_ptr<Salud> s) &&;
    Servidor&& sesiones(std::shared_ptr<Sesiones> s) &&;
    Servidor&& sin_registrar(std::vector<std::string> caminos) &&;
    // SEG-005 y SEG-009 dependen de si la conexión es segura. Sin TLS propio, lo dice quien monta
    // el servidor, y mentir aquí solo se perjudica a sí mismo.
    Servidor&& tras_tls() &&;

    // El pipeline entero **sin socket**: se le da una petición y devuelve la respuesta.
    Respuesta responder(const Peticion& peticion, std::string_view cliente = "local") const;

    int escuchar(unsigned short puerto) const;

    const Metricas& metricas() const { return *metricas_; }
    Log& log() const { return *log_; }

private:
    Respuesta atender_ruta(const Contexto& ctx, std::string_view camino) const;
    Respuesta rematar(Respuesta r, const Peticion& p, std::string_view camino,
                      std::chrono::steady_clock::time_point empezo,
                      const std::optional<Guardada>& sesion) const;

    Router router_;
    std::map<std::string, std::function<Respuesta(const Contexto&)>, std::less<>> acciones_;
    std::shared_ptr<Sesiones> sesiones_ =
        std::make_shared<Almacen>(std::chrono::minutes{30}, std::chrono::hours{8});
    Proteccion proteccion_;
    std::optional<Cors> cors_;
    std::shared_ptr<Limitador> limitador_;
    std::shared_ptr<Salud> salud_;
    std::vector<std::string> csrf_exento_;
    bool csrf_activo_ = false;
    std::vector<std::string> sin_registrar_;
    bool seguro_ = false;
    // Detrás de puntero porque llevan un candado dentro y eso haría a `Servidor` inmovible, y el
    // servidor se construye encadenando métodos que devuelven el objeto.
    std::shared_ptr<Metricas> metricas_ = std::make_shared<Metricas>();
    std::shared_ptr<Log> log_ = std::make_shared<Log>("cero", Nivel::Info);
};

}  // namespace cero
