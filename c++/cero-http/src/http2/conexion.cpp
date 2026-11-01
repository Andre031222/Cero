#include "cero/http2/conexion.hpp"

#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <thread>
#include <type_traits>
#include <utility>
#include <variant>
#include <vector>

#include "cero/formato.hpp"
#include "cero/http2/flujo.hpp"

namespace cero::http2 {
namespace {

#ifdef MSG_NOSIGNAL
constexpr int kSinSenal = MSG_NOSIGNAL;
#else
constexpr int kSinSenal = 0;
#endif

void sin_sigpipe([[maybe_unused]] int descriptor) {
#ifdef SO_NOSIGPIPE
    const int si = 1;
    ::setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &si, sizeof si);
#endif
}

bool todo(int descriptor, std::string_view datos) {
    while (!datos.empty()) {
        const auto escritos = ::send(descriptor, datos.data(), datos.size(), kSinSenal);
        if (escritos <= 0) return false;
        datos.remove_prefix(static_cast<std::size_t>(escritos));
    }
    return true;
}

std::string minuscula(std::string_view s) {
    std::string r{s};
    std::ranges::transform(r, r.begin(), [](unsigned char c) { return std::tolower(c); });
    return r;
}

bool contiene_token(std::string_view lista, std::string_view token) {
    while (!lista.empty()) {
        const auto coma = lista.find(',');
        auto pieza = lista.substr(0, coma);
        const auto desde = pieza.find_first_not_of(" \t");
        pieza = desde == std::string_view::npos ? std::string_view{} : pieza.substr(desde);
        pieza = pieza.substr(0, pieza.find_last_not_of(" \t") + 1);
        if (minuscula(pieza) == token) return true;
        if (coma == std::string_view::npos) break;
        lista.remove_prefix(coma + 1);
    }
    return false;
}

class Salida {
public:
    explicit Salida(int descriptor) : descriptor_(descriptor) {}

    bool crudo(std::string_view octetos) {
        const std::lock_guard tomado{candado_};
        return !rota_ && (todo(descriptor_, octetos) || (rota_ = true, false));
    }

    bool mandar(const std::vector<Trama>& tramas, std::uint32_t flujo = 0) {
        std::string junto;
        for (const auto& t : tramas) junto += t.octetos();
        const std::lock_guard tomado{candado_};
        if (rota_ || (flujo != 0 && anulados_.contains(flujo))) return false;
        if (!todo(descriptor_, junto)) {
            rota_ = true;
            return false;
        }
        return true;
    }

    void anular(std::uint32_t flujo) {
        const std::lock_guard tomado{candado_};
        anulados_.insert(flujo);
    }

    std::size_t max_trama() {
        const std::lock_guard tomado{candado_};
        return max_trama_;
    }

    void max_trama(std::size_t cuanto) {
        const std::lock_guard tomado{candado_};
        max_trama_ = cuanto;
    }

private:
    int descriptor_;
    std::mutex candado_;
    std::size_t max_trama_ = 16'384;
    std::set<std::uint32_t> anulados_;
    bool rota_ = false;
};

struct Comun {
    explicit Comun(int descriptor) : salida(descriptor) {}

    Salida salida;
    std::mutex candado;
    std::condition_variable avisa;
    std::vector<std::uint32_t> terminados;
    std::size_t en_vuelo = 0;
};

void responder(Salida& salida, std::uint32_t flujo, const cero::Respuesta& r, bool solo_cabeceras) {
    std::vector<Cabecera> cabeceras{{"content-length", formato("{}", r.cuerpo.size())}};
    if (!r.tipo.empty()) cabeceras.emplace_back("content-type", r.tipo);
    cabeceras.insert(cabeceras.end(), r.extra.begin(), r.extra.end());
    const auto bloque = cabeceras_de_respuesta(r.estado, cabeceras);

    const bool vacio = solo_cabeceras || r.cuerpo.empty();
    if (!salida.mandar(tramas_de_cabeceras(flujo, bloque, salida.max_trama(), vacio), flujo) ||
        vacio) {
        return;
    }
    std::string_view resto = r.cuerpo;
    while (!resto.empty()) {
        const auto cabe = std::min(resto.size(), salida.max_trama());
        const std::uint8_t banderas = cabe == resto.size() ? kFinFlujo : 0;
        if (!salida.mandar({Trama{kData, banderas, flujo, std::string{resto.substr(0, cabe)}}},
                           flujo)) {
            return;
        }
        resto.remove_prefix(cabe);
    }
}

void lanzar(const std::shared_ptr<Comun>& comun, const Atender& atender, std::uint32_t flujo,
            cero::Peticion p) {
    {
        const std::lock_guard tomado{comun->candado};
        ++comun->en_vuelo;
    }
    std::thread{[comun, &atender, flujo, p = std::move(p)] {
        const auto r = cero::metodo_conocido(p.metodo)
                           ? atender(p)
                           : cero::Respuesta::codigo(501, "método no implementado");
        responder(comun->salida, flujo, r, p.metodo == "HEAD");
        const std::lock_guard tomado{comun->candado};
        comun->terminados.push_back(flujo);
        --comun->en_vuelo;
        comun->avisa.notify_all();
    }}.detach();
}

void despedirse(int descriptor) {
    if (::shutdown(descriptor, SHUT_WR) != 0) return;
    timeval espera{};
    espera.tv_usec = 250'000;
    ::setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &espera, sizeof espera);
    char tirar[4096];
    while (::read(descriptor, tirar, sizeof tirar) > 0) {
    }
}

struct EnCurso {
    std::vector<Cabecera> cabeceras;
    std::string cuerpo;
};

}

bool DesdeSocket::llenar() {
    if (i_ == tengo_) {
        i_ = 0;
        tengo_ = 0;
    } else if (tengo_ == buf_.size()) {
        std::copy(buf_.begin() + static_cast<std::ptrdiff_t>(i_),
                  buf_.begin() + static_cast<std::ptrdiff_t>(tengo_), buf_.begin());
        tengo_ -= i_;
        i_ = 0;
    }
    if (tengo_ == buf_.size()) return false;
    const auto leidos = ::read(descriptor_, buf_.data() + tengo_, buf_.size() - tengo_);
    if (leidos <= 0) return false;
    tengo_ += static_cast<std::size_t>(leidos);
    return true;
}

int DesdeSocket::octeto() {
    if (i_ == tengo_ && !llenar()) return -1;
    return static_cast<unsigned char>(buf_[i_++]);
}

std::size_t DesdeSocket::leer(char* destino, std::size_t cuantos) {
    std::size_t copiados = 0;
    while (copiados < cuantos) {
        if (i_ == tengo_ && !llenar()) break;
        const auto cabe = std::min(cuantos - copiados, tengo_ - i_);
        std::copy_n(buf_.data() + i_, cabe, destino + copiados);
        i_ += cabe;
        copiados += cabe;
    }
    return copiados;
}

bool DesdeSocket::asomar(std::string_view prefijo) {
    while (true) {
        const std::string_view visto{buf_.data() + i_, std::min(tengo_ - i_, prefijo.size())};
        if (visto != prefijo.substr(0, visto.size())) return false;
        if (visto.size() == prefijo.size()) return true;
        if (!llenar()) return false;
    }
}

std::optional<std::string> base64url(std::string_view texto) {
    while (texto.ends_with('=')) texto.remove_suffix(1);
    std::string salida;
    std::uint32_t acumulado = 0;
    int bits = 0;
    for (const char c : texto) {
        std::uint32_t v = 0;
        if (c >= 'A' && c <= 'Z') v = static_cast<std::uint32_t>(c - 'A');
        else if (c >= 'a' && c <= 'z') v = static_cast<std::uint32_t>(c - 'a' + 26);
        else if (c >= '0' && c <= '9') v = static_cast<std::uint32_t>(c - '0' + 52);
        else if (c == '-') v = 62;
        else if (c == '_') v = 63;
        else return std::nullopt;
        acumulado = (acumulado << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            salida += static_cast<char>((acumulado >> bits) & 0xff);
        }
    }
    return salida;
}

bool pide_h2c(const cero::Peticion& p) {
    if (p.version != "HTTP/1.1") return false;
    const auto ascenso = p.cabecera("upgrade");
    const auto ajustes = p.cabecera("http2-settings");
    const auto conexion = p.cabecera("connection");
    if (!ascenso || !ajustes || !conexion) return false;
    if (minuscula(*ascenso) != "h2c" || !contiene_token(*conexion, "upgrade") ||
        !contiene_token(*conexion, "http2-settings")) {
        return false;
    }
    const auto carga = base64url(*ajustes);
    return carga && carga->size() % 6 == 0;
}

std::vector<Trama> tramas_de_cabeceras(std::uint32_t flujo, std::string_view bloque,
                                       std::size_t max, bool fin) {
    std::vector<Trama> tramas;
    std::uint8_t tipo = kHeaders;
    std::uint8_t banderas = fin ? kFinFlujo : 0;
    do {
        const auto cabe = std::min(bloque.size(), max);
        if (cabe == bloque.size()) banderas |= kFinCabeceras;
        tramas.push_back(Trama{tipo, banderas, flujo, std::string{bloque.substr(0, cabe)}});
        bloque.remove_prefix(cabe);
        tipo = kContinuation;
        banderas = 0;
    } while (!bloque.empty());
    return tramas;
}

cero::Peticion armar(std::vector<Cabecera> cabeceras, std::string cuerpo) {
    cero::Peticion p;
    p.version = "HTTP/2";
    p.destino = "/";
    p.cuerpo = std::move(cuerpo);
    for (auto& [nombre, valor] : cabeceras) {
        if (nombre == ":method") {
            p.metodo = std::move(valor);
        } else if (nombre == ":path") {
            p.destino = std::move(valor);
        } else if (nombre == ":authority") {
            p.cabeceras.insert_or_assign("host", std::move(valor));
        } else if (!nombre.starts_with(':')) {
            const auto previo = p.cabeceras.find(nombre);
            if (previo == p.cabeceras.end()) {
                p.cabeceras.emplace(std::move(nombre), std::move(valor));
            } else {
                previo->second += (nombre == "cookie" ? "; " : ", ") + valor;
            }
        }
    }
    return p;
}

void servir(DesdeSocket& entrada, Ajustes mios, const Atender& atender,
            std::optional<cero::Peticion> primera) {
    const int descriptor = entrada.descriptor();
    sin_sigpipe(descriptor);
    auto comun = std::make_shared<Comun>(descriptor);
    auto& salida = comun->salida;

    if (primera && !salida.crudo(kAscenso)) return;
    salida.mandar({mios.trama()});

    std::string preambulo(kPreambulo.size(), '\0');
    if (entrada.leer(preambulo.data(), preambulo.size()) != preambulo.size() ||
        preambulo != kPreambulo) {
        salida.mandar({Trama::goaway(0, Error::Protocolo)});
        despedirse(descriptor);
        return;
    }

    Sesion sesion{mios};
    std::map<std::uint32_t, EnCurso> en_curso;

    if (primera) {
        const auto carga = base64url(primera->cabecera("http2-settings").value_or(""));
        if (!carga || !sesion.ascender(*carga)) {
            salida.mandar({Trama::goaway(0, Error::Protocolo)});
            despedirse(descriptor);
            return;
        }
        salida.max_trama(sesion.par().max_trama);
        lanzar(comun, atender, 1, std::move(*primera));
    }

    while (true) {
        const auto t = leer_trama(entrada, sesion.mios().max_trama);
        if (!t) {
            if (t.error().codigo != Error::Ninguno) {
                salida.mandar({Trama::goaway(sesion.ultimo(), t.error().codigo)});
            }
            break;
        }

        {
            const std::lock_guard tomado{comun->candado};
            for (const auto flujo : comun->terminados) sesion.respondido(flujo);
            comun->terminados.clear();
        }

        auto d = sesion.recibir(*t);
        if (!d) {
            salida.mandar({Trama::goaway(sesion.ultimo(), d.error().codigo)});
            break;
        }
        if (t->tipo == kSettings) salida.max_trama(sesion.par().max_trama);
        if (d->cortado) {
            en_curso.erase(d->cortado->flujo);
            salida.anular(d->cortado->flujo);
        }

        for (auto& accion : d->acciones) {
            std::visit(
                [&](auto& a) {
                    using A = std::decay_t<decltype(a)>;
                    if constexpr (std::is_same_v<A, Escribir>) {
                        salida.mandar({a.trama});
                    } else if constexpr (std::is_same_v<A, Anulado>) {
                        en_curso.erase(a.flujo);
                        salida.anular(a.flujo);
                    } else if constexpr (std::is_same_v<A, Peticion>) {
                        en_curso[a.flujo] = EnCurso{std::move(a.cabeceras), {}};
                    } else if constexpr (std::is_same_v<A, Cuerpo>) {
                        if (const auto i = en_curso.find(a.flujo); i != en_curso.end()) {
                            i->second.cuerpo += a.datos;
                        }
                    }
                    std::uint32_t flujo = 0;
                    bool fin = false;
                    if constexpr (std::is_same_v<A, Peticion> || std::is_same_v<A, Cuerpo>) {
                        flujo = a.flujo;
                        fin = a.fin;
                    }
                    if (!fin) return;
                    const auto i = en_curso.find(flujo);
                    if (i == en_curso.end()) return;
                    auto p = armar(std::move(i->second.cabeceras), std::move(i->second.cuerpo));
                    en_curso.erase(i);
                    lanzar(comun, atender, flujo, std::move(p));
                },
                accion);
        }
    }

    {
        std::unique_lock tomado{comun->candado};
        comun->avisa.wait(tomado, [&] { return comun->en_vuelo == 0; });
    }
    despedirse(descriptor);
}

}
