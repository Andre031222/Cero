#pragma once

#include <charconv>
#include <chrono>
#include <concepts>
#include <cstddef>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <system_error>
#include <type_traits>
#include <utility>
#include <vector>

#include "cero/fallo.hpp"
#include "cero/json.hpp"
#include "cero/observabilidad.hpp"
#include "cero/peticion.hpp"
#include "cero/respuesta.hpp"
#include "cero/ruta.hpp"
#include "cero/seguridad.hpp"
#include "cero/sesion.hpp"
#include "cero/socket.hpp"

namespace cero {

class Servidor;

template <class T>
concept EnteroDeRuta =
    std::integral<T> && !std::same_as<std::remove_cv_t<T>, bool> &&
    !std::same_as<std::remove_cv_t<T>, char> && !std::same_as<std::remove_cv_t<T>, signed char> &&
    !std::same_as<std::remove_cv_t<T>, unsigned char> && !std::same_as<std::remove_cv_t<T>, wchar_t> &&
    !std::same_as<std::remove_cv_t<T>, char8_t> && !std::same_as<std::remove_cv_t<T>, char16_t> &&
    !std::same_as<std::remove_cv_t<T>, char32_t>;

template <class T>
concept DesdeJson = requires(const Json& j) {
    { T::de_json(j) } -> std::same_as<std::optional<T>>;
};

class Contexto {
public:
    const Peticion& peticion;
    const Captura& variables;

    std::optional<std::string_view> variable(std::string_view nombre) const;
    std::optional<std::string_view> cabecera(std::string_view nombre) const;
    std::optional<std::string> consulta(std::string_view nombre) const;
    std::string consulta_o(std::string_view nombre, std::string_view defecto) const;
    std::optional<std::string> campo(std::string_view nombre) const;
    Json cuerpo_json() const;

    template <EnteroDeRuta T>
    T variable_como(std::string_view nombre) const {
        const auto texto = variable(nombre).value_or("");
        const auto fin = texto.data() + texto.size();
        T valor{};
        const auto [resto, error] = std::from_chars(texto.data(), fin, valor);
        if (texto.empty() || error != std::errc{} || resto != fin) {
            throw Fallo{400, "la variable " + std::string{nombre} + " no tiene el tipo esperado"};
        }
        return valor;
    }

    template <DesdeJson T>
    T cuerpo_como() const {
        auto valor = T::de_json(cuerpo_json());
        if (!valor) throw Fallo{422, "el cuerpo no tiene la forma esperada"};
        return std::move(*valor);
    }

    const std::optional<Guardada>& sesion() const { return llegada_; }
    std::optional<Guardada> abrir_sesion() const;
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

using Accion = std::function<Respuesta(const Contexto&)>;
using Medio = std::function<Respuesta(const Contexto&, const Accion&)>;
using AlFallar = std::function<Respuesta(const Contexto&, const Fallo&)>;

template <class F>
Respuesta en_respuesta(const F& f, const Contexto& c) {
    using Devuelto = std::invoke_result_t<const F&, const Contexto&>;
    if constexpr (std::is_void_v<Devuelto>) {
        f(c);
        return Respuesta::nada();
    } else if constexpr (std::same_as<Devuelto, Json>) {
        return Respuesta::json(f(c).escribir());
    } else if constexpr (std::convertible_to<Devuelto, std::string_view>) {
        return Respuesta::texto(f(c));
    } else {
        return f(c);
    }
}

class Servidor {
public:
    explicit Servidor(Router router) : router_(std::move(router)) {}

    template <class F>
    Servidor&& accion(std::string_view nombre, F f) && {
        acciones_.emplace(nombre,
                          [f = std::move(f)](const Contexto& c) { return en_respuesta(f, c); });
        return std::move(*this);
    }

    Servidor&& usar(Medio m) &&;
    Servidor&& al_fallar(AlFallar f) &&;
    Servidor&& por_defecto(Accion f) &&;
    Servidor&& proteccion(Proteccion p) &&;
    Servidor&& cors(Cors c) &&;
    Servidor&& limite(unsigned cupo, std::chrono::seconds ventana) &&;
    Servidor&& csrf(std::vector<std::string> exenciones) &&;
    Servidor&& salud(std::shared_ptr<Salud> s) &&;
    Servidor&& sesiones(std::shared_ptr<Sesiones> s) &&;
    Servidor&& sin_registrar(std::vector<std::string> caminos) &&;
    Servidor&& plazos(Plazos p) &&;
    Servidor&& limites(Limites l) &&;
    Servidor&& direccion(std::string d) &&;
    Servidor&& tras_tls() &&;

    Respuesta responder(const Peticion& peticion, std::string_view cliente = "local") const;

    int escuchar(unsigned short puerto) const;

    const Metricas& metricas() const { return *metricas_; }
    Log& log() const { return *log_; }

private:
    Respuesta despachar(const Peticion& p, std::string_view cliente) const;
    void conversar(int cliente, const std::string& desde, Plazos plazos, Limites limites) const;
    Respuesta cadena(std::size_t i, const Contexto& ctx, std::string_view camino,
                     const Resolucion& resolucion) const;
    Respuesta atender_ruta(const Contexto& ctx, std::string_view camino,
                           const Resolucion& resolucion) const;
    Respuesta actuar(const Contexto& ctx, std::string_view nombre, std::string_view ruta) const;
    Respuesta del_fallo(const Contexto& ctx, std::string_view ruta, const Fallo& fallo) const;
    std::string patron(const Peticion& p, std::string_view camino) const;
    Respuesta rematar(Respuesta r, const Peticion& p, std::string_view camino,
                      std::chrono::steady_clock::time_point empezo,
                      const std::optional<Guardada>& sesion) const;

    Router router_;
    std::map<std::string, Accion, std::less<>> acciones_;
    std::vector<Medio> medios_;
    AlFallar al_fallar_;
    Accion por_defecto_;
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
    Plazos plazos_;
    Limites limites_;
    std::string direccion_ = "0.0.0.0";
    std::shared_ptr<Metricas> metricas_ = std::make_shared<Metricas>();
    std::shared_ptr<Log> log_ = std::make_shared<Log>("cero", Nivel::Info);
};

}
