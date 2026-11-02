#pragma once

#include <chrono>
#include <cstddef>
#include <filesystem>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>

#include "cero/sesion.hpp"

namespace cero {

class AlmacenEnDisco final : public Sesiones {
public:
    AlmacenEnDisco(std::filesystem::path carpeta, std::chrono::seconds inactividad,
                   std::optional<std::chrono::seconds> vida_maxima);

    std::optional<Guardada> recuperar(std::optional<std::string_view> id) override;
    std::optional<Guardada> crear() override;
    std::optional<std::string> rotar(const Guardada& g) override;
    std::size_t cuantas() const override;
    void guardar(const Guardada& g) override;

    const std::filesystem::path& carpeta() const { return carpeta_; }

private:
    struct Viva {
        std::weak_ptr<Sesion> sesion;
        std::weak_ptr<std::mutex> candado;
    };

    std::optional<Guardada> de_disco(std::string_view id) const;
    void anotar(std::string_view id, const Guardada& g);
    bool escribir(const Sesion& s) const;
    void borrar(std::string_view id) const;

    std::filesystem::path carpeta_;
    Caducidad caducidad_;
    std::mutex candado_;
    std::map<std::string, Viva, std::less<>> vivas_;
};

}
