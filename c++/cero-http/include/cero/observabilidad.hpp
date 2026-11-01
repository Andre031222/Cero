#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace cero {

struct Comprobado {
    bool bien;
    std::string motivo;

    static Comprobado va() { return {true, {}}; }
    static Comprobado falla(std::string_view porque) { return {false, std::string{porque}}; }
};

struct Informe {
    unsigned estado;
    std::string cuerpo;
};

class Salud {
public:
    Salud& comprobacion(std::string_view nombre, std::function<Comprobado()> f);

    Informe vivo() const;
    Informe listo() const;

    bool publico = false;

private:
    std::chrono::steady_clock::time_point arranque_ = std::chrono::steady_clock::now();
    std::vector<std::pair<std::string, std::function<Comprobado()>>> comprobaciones_;
};

enum class Nivel { Traza, Depuracion, Info, Aviso, Error, Nada };

class Log {
public:
    Log(std::string_view origen, Nivel nivel) : origen_(origen), nivel_(nivel) {}

    void escribir(Nivel nivel, std::string_view plantilla,
                  const std::vector<std::string_view>& valores = {});

    void con_error(std::string_view plantilla, const std::vector<std::string_view>& valores,
                   std::string_view tipo, std::string_view mensaje);

    std::vector<std::string> lineas() const;
    Nivel nivel() const { return nivel_; }

private:
    std::string origen_;
    Nivel nivel_;
    mutable std::mutex candado_;
    std::vector<std::string> lineas_;
};

std::string interpolar(std::string_view plantilla,
                       const std::vector<std::string_view>& valores);

class Metricas {
public:
    void ignorar(std::string_view patron);
    void anotar(std::string_view patron, unsigned estado, std::chrono::microseconds tardo);

    std::uint64_t total() const { return total_; }
    std::uint64_t peticiones(std::string_view patron) const;
    std::uint64_t errores(std::string_view patron) const;
    std::optional<std::uint64_t> percentil(std::string_view patron, double p) const;
    std::size_t patrones() const;
    std::string json() const;

private:
    struct Cuenta {
        std::uint64_t peticiones = 0;
        std::uint64_t errores = 0;
        std::vector<std::uint64_t> micros;
    };
    mutable std::shared_mutex candado_;
    std::map<std::string, Cuenta, std::less<>> por_patron_;
    std::vector<std::string> ignoradas_;
    std::atomic<std::uint64_t> total_{0};
    std::chrono::steady_clock::time_point arranque_ = std::chrono::steady_clock::now();
};

std::string linea_acceso(std::string_view metodo, std::string_view destino, unsigned estado,
                         std::optional<std::string_view> usuario,
                         std::chrono::microseconds tardo);

}
