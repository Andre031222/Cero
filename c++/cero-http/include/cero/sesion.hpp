#pragma once

#include <chrono>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <string>
#include <string_view>

namespace cero {

using Reloj = std::chrono::system_clock;

// Reloj de pared y no `steady_clock`: una sesión que viva en una tabla la leen dos instancias, y un
// reloj monótono es local al proceso. Es `SES-012` y `SES-013` decidiendo el tipo.
class Sesion {
public:
    const std::string& id() const { return id_; }
    bool viva() const { return viva_; }
    Reloj::time_point creada() const { return creada_; }
    Reloj::time_point tocada() const { return tocada_; }
    const std::map<std::string, std::string, std::less<>>& atributos() const { return atributos_; }

    // SES-004: leer o escribir una invalidada falla en vez de recrearla en silencio.
    bool poner(std::string_view clave, std::string_view valor);
    std::optional<std::string_view> leer(std::string_view clave) const;
    void invalidar();

    // SES-008 y SES-011: consultarlo **marca la cookie como emitida**, así que no es una lectura
    // pura. Que no sea `const` es lo que impide llamarlo dos veces por descuido desde dos caminos
    // de salida, que es exactamente el fallo que la 0.6.0 tuvo en Java entre HTTP/1.1 y HTTP/2.
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

// SES-007: el candado va **dentro** de la sesión y no alrededor del almacén. Dos peticiones
// simultáneas sobre la misma sesión no pueden perder escrituras, y dos sobre sesiones distintas no
// tienen por qué esperarse.
struct Guardada {
    std::shared_ptr<Sesion> sesion;
    std::shared_ptr<std::mutex> candado;

    auto tomar() const { return std::unique_lock{*candado}; }
};

// Lo que el servidor le pide a un almacén, sea el de memoria o uno en una tabla. Que sea una
// interfaz es lo que hace cumplible `SES-013`: el nombre de la tabla es asunto de quien implementa.
class Sesiones {
public:
    virtual ~Sesiones() = default;
    virtual std::optional<Guardada> recuperar(std::optional<std::string_view> id) = 0;
    virtual std::optional<Guardada> crear() = 0;
    virtual std::optional<std::string> rotar(const Guardada& g) = 0;
    virtual std::size_t cuantas() const = 0;

    // Se llama una vez por respuesta, en el mismo sitio que emite la cookie.
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

// SES-002: al menos 40 caracteres de una fuente apta para criptografía. Java tiene `SecureRandom`;
// aquí se lee la del sistema operativo, que es lo que `SecureRandom` hace por debajo. El requisito
// habla de la propiedad, no del nombre de la clase.
std::optional<std::string> identificador();

// SES-009: `HttpOnly` y `SameSite=Lax` siempre; `Secure` cuando y solo cuando hay TLS.
std::string cabecera_cookie(std::string_view id, bool seguro);
std::optional<std::string_view> id_de_cookie(std::optional<std::string_view> cabecera);

}  // namespace cero
