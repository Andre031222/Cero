#pragma once

#include <chrono>
#include <memory>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <string>
#include <string_view>

#include "cero/tipos.hpp"

namespace cero {

using Reloj = std::chrono::system_clock;
using Atributos = PorNombre<std::string>;

struct Caducidad {
    std::chrono::seconds inactividad;
    std::optional<std::chrono::seconds> vida_maxima;

    bool vencida(Reloj::time_point creada, Reloj::time_point tocada) const;
};

class Sesion {
public:
    const std::string& id() const { return id_; }
    bool viva() const { return viva_; }
    Reloj::time_point creada() const { return creada_; }
    Reloj::time_point tocada() const { return tocada_; }
    const Atributos& atributos() const { return atributos_; }

    bool poner(std::string_view clave, std::string_view valor);
    std::optional<std::string_view> leer(std::string_view clave) const;
    void invalidar();

    std::optional<std::string> cookie_pendiente();
    bool sucia();
    std::optional<std::string> renovar_id();

    static std::shared_ptr<Sesion> nueva();
    static std::shared_ptr<Sesion> rescatada(std::string_view id, Atributos atributos,
                                             Reloj::time_point creada, Reloj::time_point tocada);

private:
    std::string id_;
    Atributos atributos_;
    Reloj::time_point creada_{};
    Reloj::time_point tocada_{};
    bool viva_ = true;
    bool cookie_pendiente_ = true;
    bool sucia_ = true;
};

struct Guardada {
    std::shared_ptr<Sesion> sesion;
    std::shared_ptr<std::mutex> candado;

    auto tomar() const { return std::unique_lock{*candado}; }
};

class Sesiones {
public:
    virtual ~Sesiones() = default;
    virtual std::optional<Guardada> recuperar(std::optional<std::string_view> id) = 0;
    virtual std::optional<Guardada> crear() = 0;
    virtual std::optional<std::string> rotar(const Guardada& g) = 0;
    virtual std::size_t cuantas() const = 0;

    virtual void guardar(const Guardada&) {}
};

class Almacen final : public Sesiones {
public:
    Almacen(std::chrono::seconds inactividad, std::optional<std::chrono::seconds> vida_maxima)
        : caducidad_{inactividad, vida_maxima} {}

    std::optional<Guardada> recuperar(std::optional<std::string_view> id) override;
    std::optional<Guardada> crear() override;
    std::optional<std::string> rotar(const Guardada& g) override;
    std::size_t cuantas() const override;

private:
    mutable std::shared_mutex candado_;
    PorNombre<Guardada> sesiones_;
    Caducidad caducidad_;
};

std::optional<std::string> identificador();

std::string cabecera_cookie(std::string_view id, bool seguro);
std::optional<std::string_view> id_de_cookie(std::optional<std::string_view> cabecera);

}
