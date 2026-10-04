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

// ── Salud · OBS-001 a OBS-007 ───────────────────────────────────────────────

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

    // OBS-001: responde 200 mientras el proceso responda. **No admite comprobaciones**: si las
    // aceptara dejaría de medir lo que dice medir, y un supervisor reiniciaría el proceso por una
    // base de datos lenta — cambiando un problema pasajero por una caída.
    Informe vivo() const;
    Informe listo() const;

    // OBS-006: un endpoint de salud es alcanzable desde fuera y enumera la infraestructura interna
    // a quien pregunte. En modo público el código no cambia; el detalle sí.
    bool publico = false;

private:
    std::chrono::steady_clock::time_point arranque_ = std::chrono::steady_clock::now();
    std::vector<std::pair<std::string, std::function<Comprobado()>>> comprobaciones_;
};

// ── Registro · OBS-008 a OBS-012 ────────────────────────────────────────────

enum class Nivel { Traza, Depuracion, Info, Aviso, Error, Nada };

class Log {
public:
    Log(std::string_view origen, Nivel nivel) : origen_(origen), nivel_(nivel) {}

    void escribir(Nivel nivel, std::string_view plantilla,
                  const std::vector<std::string_view>& valores = {});

    // OBS-010: el tipo **y** el mensaje. El tipo importa tanto y se olvida más: dos errores
    // distintos pueden decir «no such file» y el que hay que arreglar es otro según de dónde
    // venga. Se pasa a mano porque en C++ el nombre de un tipo en ejecución viene decorado y
    // deshacerlo no es portable: pedirlo explícito es más honesto que adivinarlo.
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

// OBS-011: con valores de menos se conserva el marcador; con valores de más se ignoran.
// Interpolar NO puede fallar nunca: un log que revienta se lleva por delante lo que iba a contar.
std::string interpolar(std::string_view plantilla,
                       const std::vector<std::string_view>& valores);

// ── Métricas · OBS-013 a OBS-018 ────────────────────────────────────────────

class Metricas {
public:
    void ignorar(std::string_view patron);
    void anotar(std::string_view patron, unsigned estado, std::chrono::microseconds tardo);

    std::uint64_t total() const { return total_; }
    std::uint64_t peticiones(std::string_view patron) const;
    std::uint64_t errores(std::string_view patron) const;
    // OBS-015: percentiles y no solo la media. La media esconde la cola, que es donde vive el
    // usuario que se queja.
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
    // OBS-014: la clave es el **patrón**, no la URL. Con la URL, `/usuarios/{id}` genera tantas
    // series como identificadores existan: cardinalidad sin acotar desde entrada externa, la
    // misma familia que el hallazgo del limitador pero contra el sistema de métricas.
    std::map<std::string, Cuenta, std::less<>> por_patron_;
    std::vector<std::string> ignoradas_;
    std::atomic<std::uint64_t> total_{0};
    std::chrono::steady_clock::time_point arranque_ = std::chrono::steady_clock::now();
};

// ── Log de acceso · OBS-019 a OBS-023 ───────────────────────────────────────

// El usuario sin identificar va con una marca explícita y no con un hueco: un campo vacío en una
// línea separada por espacios corre las columnas siguientes y desalinea el fichero entero.
std::string linea_acceso(std::string_view metodo, std::string_view destino, unsigned estado,
                         std::optional<std::string_view> usuario,
                         std::chrono::microseconds tardo);

}  // namespace cero
