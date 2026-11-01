#pragma once

#include <chrono>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <string>
#include <string_view>

namespace cero {

using Reloj = std::chrono::system_clock;

class Sesion {
public:
    const std::string& id() const { return id_; }
    bool viva() const { return viva_; }
    Reloj::time_point creada() const { return creada_; }
    Reloj::time_point tocada() const { return tocada_; }
    const std::map<std::string, std::string, std::less<>>& atributos() const { return atributos_; }

    bool poner(std::string_view clave, std::string_view valor);
    std::optional<std::string_view> leer(std::string_view clave) const;
    void invalidar();

    std::optional<std::string> cookie_pendiente();
    bool sucia();

    static std::shared_ptr<Sesion> rescatada(std::string_view id,
                                             std::map<std::string, std::string, std::less<>> atributos,
                                             Reloj::time_point creada, Reloj::time_point tocada);

private:
    friend class Almacen;
    std::string id_;
    std::map<std::string, std::string, std::less<>> atributos_;
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
        : inactividad_(inactividad), vida_maxima_(vida_maxima) {}

    std::optional<Guardada> recuperar(std::optional<std::string_view> id) override;
    std::optional<Guardada> crear() override;
    std::optional<std::string> rotar(const Guardada& g) override;
    std::size_t cuantas() const override;

    bool caducada(Reloj::time_point creada, Reloj::time_point tocada) const;

private:
    mutable std::shared_mutex candado_;
    std::map<std::string, Guardada, std::less<>> sesiones_;
    std::chrono::seconds inactividad_;
    std::optional<std::chrono::seconds> vida_maxima_;
};

std::optional<std::string> identificador();

std::string cabecera_cookie(std::string_view id, bool seguro);
std::optional<std::string_view> id_de_cookie(std::optional<std::string_view> cabecera);

}
