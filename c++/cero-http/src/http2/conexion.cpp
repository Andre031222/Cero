#include "cero/http2/conexion.hpp"

#include <poll.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <charconv>
#include <cerrno>
#include <chrono>
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
#include <system_error>
#include <thread>
#include <type_traits>
#include <utility>
#include <variant>
#include <vector>

#include "cero/formato.hpp"
#include "cero/http2/flujo.hpp"
#include "cero/http2/ventanas.hpp"
#include "cero/socket.hpp"

using namespace std;

namespace cero::http2 {
namespace {

bool contiene_token(string_view lista, string_view token) {
    while (!lista.empty()) {
        const auto coma = lista.find(',');
        auto pieza = lista.substr(0, coma);
        const auto desde = pieza.find_first_not_of(" \t");
        pieza = desde == string_view::npos ? string_view{} : pieza.substr(desde);
        pieza = pieza.substr(0, pieza.find_last_not_of(" \t") + 1);
        if (minuscula(pieza) == token) return true;
        if (coma == string_view::npos) break;
        lista.remove_prefix(coma + 1);
    }
    return false;
}

class Salida {
public:
    Salida(int descriptor, cero::Plazos plazos) : descriptor_(descriptor), plazos_(plazos) {}

    bool crudo(string_view octetos) {
        const lock_guard tomado{candado_};
        return escribir(octetos);
    }

    void despedir(const Trama& goaway) {
        const lock_guard tomado{candado_};
        if (escribir(goaway.octetos())) shutdown(descriptor_, SHUT_WR);
        rota_ = true;
    }

    bool mandar(const vector<Trama>& tramas, uint32_t flujo = 0, bool ultima = false) {
        string junto;
        for (const auto& t : tramas) junto += t.octetos();
        const lock_guard tomado{candado_};
        if (flujo != 0 && anulados_.contains(flujo)) return false;
        if (!escribir(junto)) return false;
        if (ultima) despachar(flujo);
        return true;
    }

    void empezar(uint32_t flujo) {
        const lock_guard tomado{candado_};
        en_marcha_.insert(flujo);
    }

    void terminar(uint32_t flujo) {
        const lock_guard tomado{candado_};
        despachar(flujo);
    }

    void anular(uint32_t flujo) {
        const lock_guard tomado{candado_};
        if (en_marcha_.contains(flujo)) anulados_.insert(flujo);
    }

    vector<uint32_t> recoger() {
        const lock_guard tomado{candado_};
        return exchange(terminados_, {});
    }

    size_t max_trama() {
        const lock_guard tomado{candado_};
        return max_trama_;
    }

    void max_trama(size_t cuanto) {
        const lock_guard tomado{candado_};
        max_trama_ = cuanto;
    }

private:
    bool escribir(string_view octetos) {
        if (rota_) return false;
        if (mandar_todo(descriptor_, octetos, plazos_)) return true;
        rota_ = true;
        shutdown(descriptor_, SHUT_RDWR);
        return false;
    }

    void despachar(uint32_t flujo) {
        en_marcha_.erase(flujo);
        anulados_.erase(flujo);
        terminados_.push_back(flujo);
    }

    int descriptor_;
    cero::Plazos plazos_;
    mutex candado_;
    size_t max_trama_ = 16'384;
    set<uint32_t> en_marcha_;
    set<uint32_t> anulados_;
    vector<uint32_t> terminados_;
    bool rota_ = false;
};

struct Comun {
    Comun(int descriptor, cero::Plazos p, cero::Limites l) : salida(descriptor, p), plazos(p), limites(l) {}

    bool cargar(size_t octetos) {
        const lock_guard tomado{candado};
        if (por_mandar > 0 && por_mandar + octetos > limites.en_vuelo) return false;
        por_mandar += octetos;
        return true;
    }

    void descargar(size_t octetos) {
        const lock_guard tomado{candado};
        por_mandar -= octetos;
    }

    void calmar() {
        calma = true;
        salida.despedir(Trama::goaway(ultimo, Error::Calma));
        ventanas.cerrar();
    }

    Salida salida;
    Ventanas ventanas;
    cero::Plazos plazos;
    cero::Limites limites;
    mutex candado;
    condition_variable avisa;
    size_t en_vuelo = 0;
    size_t por_mandar = 0;
    atomic<uint32_t> ultimo{0};
    atomic<bool> calma{false};
};

class PorMandar {
public:
    PorMandar(Comun& comun, size_t octetos) : comun_(comun), octetos_(octetos) {}
    PorMandar(const PorMandar&) = delete;
    PorMandar& operator=(const PorMandar&) = delete;
    ~PorMandar() { comun_.descargar(octetos_); }

    void mandados(size_t octetos) {
        comun_.descargar(octetos);
        octetos_ -= octetos;
    }

private:
    Comun& comun_;
    size_t octetos_;
};

bool responder(Comun& comun, uint32_t flujo, const cero::Respuesta& r, bool solo_cabeceras) {
    auto& salida = comun.salida;
    Cabeceras cabeceras{{"content-length", formato("{}", r.cuerpo.size())}};
    if (!r.tipo.empty()) cabeceras.emplace_back("content-type", r.tipo);
    cabeceras.insert(cabeceras.end(), r.extra.begin(), r.extra.end());
    const auto bloque = cabeceras_de_respuesta(r.estado, cabeceras);

    const bool vacio = solo_cabeceras || r.cuerpo.empty();
    if (!salida.mandar(tramas_de_cabeceras(flujo, bloque, salida.max_trama(), vacio), flujo, vacio)) {
        return false;
    }
    string_view resto = vacio ? string_view{} : string_view{r.cuerpo};
    if (!comun.cargar(resto.size())) {
        comun.calmar();
        return false;
    }
    PorMandar pendiente{comun, resto.size()};
    while (!resto.empty()) {
        auto trama = resto.substr(0, salida.max_trama());
        const auto hasta = chrono::steady_clock::now() + comun.plazos.escritura;
        while (!trama.empty()) {
            const auto cabe = comun.ventanas.reservar(flujo, trama.size(), hasta);
            if (!cabe) {
                if (chrono::steady_clock::now() >= hasta) {
                    salida.mandar({Trama::rst(flujo, Error::Anulado)});
                }
                return false;
            }
            const bool fin = *cabe == resto.size();
            const uint8_t banderas = fin ? kFinFlujo : 0;
            if (!salida.mandar({Trama{kData, banderas, flujo, string{resto.substr(0, *cabe)}}},
                               flujo, fin)) {
                return false;
            }
            resto.remove_prefix(*cabe);
            trama.remove_prefix(*cabe);
            pendiente.mandados(*cabe);
        }
    }
    return true;
}

void lanzar(const shared_ptr<Comun>& comun, const Atender& atender, uint32_t flujo,
            cero::Peticion p) {
    const auto acabar = [comun, flujo](bool despachado) {
        if (!despachado) comun->salida.terminar(flujo);
        const lock_guard tomado{comun->candado};
        --comun->en_vuelo;
        comun->avisa.notify_all();
    };
    {
        const lock_guard tomado{comun->candado};
        ++comun->en_vuelo;
    }
    comun->salida.empezar(flujo);
    try {
        thread{[comun, &atender, flujo, acabar, p = std::move(p)] {
            bool despachado = false;
            try {
                const auto r = cero::metodo_conocido(p.metodo)
                                   ? atender(p)
                                   : cero::Respuesta::codigo(501, "método no implementado");
                despachado = responder(*comun, flujo, r, p.metodo == "HEAD");
            } catch (...) {
                comun->salida.mandar({Trama::rst(flujo, Error::Interno)});
            }
            acabar(despachado);
        }}.detach();
    } catch (const system_error&) {
        comun->salida.mandar({Trama::rst(flujo, Error::Rechazado)});
        acabar(false);
    }
}

void despedirse(int descriptor) {
    if (shutdown(descriptor, SHUT_WR) != 0) return;
    timeval espera{};
    espera.tv_usec = 250'000;
    setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &espera, sizeof espera);
    const auto hasta = chrono::steady_clock::now() + chrono::seconds{1};
    char tirar[4096];
    while (chrono::steady_clock::now() < hasta && read(descriptor, tirar, sizeof tirar) > 0) {
    }
}

bool anuncia_de_mas(const Cabeceras& cabeceras, size_t tope) {
    for (const auto& [nombre, valor] : cabeceras) {
        if (nombre != "content-length") continue;
        uint64_t largo = 0;
        from_chars(valor.data(), valor.data() + valor.size(), largo);
        return largo > tope;
    }
    return false;
}

struct EnCurso {
    Cabeceras cabeceras;
    string cuerpo;
};

}

bool DesdeSocket::llenar() {
    if (i_ == tengo_) {
        i_ = 0;
        tengo_ = 0;
    } else if (tengo_ == buf_.size()) {
        copy(buf_.begin() + static_cast<ptrdiff_t>(i_),
                  buf_.begin() + static_cast<ptrdiff_t>(tengo_), buf_.begin());
        tengo_ -= i_;
        i_ = 0;
    }
    if (tengo_ == buf_.size()) return false;
    const auto leidos = read(descriptor_, buf_.data() + tengo_, buf_.size() - tengo_);
    if (leidos <= 0) return false;
    tengo_ += static_cast<size_t>(leidos);
    return true;
}

int DesdeSocket::octeto() {
    if (i_ == tengo_ && !llenar()) return -1;
    return static_cast<unsigned char>(buf_[i_++]);
}

size_t DesdeSocket::leer(char* destino, size_t cuantos) {
    size_t copiados = 0;
    while (copiados < cuantos) {
        if (i_ == tengo_ && !llenar()) break;
        const auto cabe = min(cuantos - copiados, tengo_ - i_);
        copy_n(buf_.data() + i_, cabe, destino + copiados);
        i_ += cabe;
        copiados += cabe;
    }
    return copiados;
}

bool DesdeSocket::esperar(chrono::milliseconds cuanto) {
    if (i_ < tengo_) return true;
    pollfd mira{descriptor_, POLLIN, 0};
    while (true) {
        const int listo = poll(&mira, 1, static_cast<int>(cuanto.count()));
        if (listo < 0 && errno == EINTR) continue;
        return listo != 0;
    }
}

bool DesdeSocket::asomar(string_view prefijo) {
    while (true) {
        const string_view visto{buf_.data() + i_, min(tengo_ - i_, prefijo.size())};
        if (visto != prefijo.substr(0, visto.size())) return false;
        if (visto.size() == prefijo.size()) return true;
        if (!llenar()) return false;
    }
}

optional<string> base64url(string_view texto) {
    while (texto.ends_with('=')) texto.remove_suffix(1);
    string salida;
    uint32_t acumulado = 0;
    int bits = 0;
    for (const char c : texto) {
        uint32_t v = 0;
        if (c >= 'A' && c <= 'Z') v = static_cast<uint32_t>(c - 'A');
        else if (c >= 'a' && c <= 'z') v = static_cast<uint32_t>(c - 'a' + 26);
        else if (c >= '0' && c <= '9') v = static_cast<uint32_t>(c - '0' + 52);
        else if (c == '-') v = 62;
        else if (c == '_') v = 63;
        else return nullopt;
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

vector<Trama> tramas_de_cabeceras(uint32_t flujo, string_view bloque,
                                       size_t max, bool fin) {
    vector<Trama> tramas;
    uint8_t tipo = kHeaders;
    uint8_t banderas = fin ? kFinFlujo : 0;
    do {
        const auto cabe = min(bloque.size(), max);
        if (cabe == bloque.size()) banderas |= kFinCabeceras;
        tramas.push_back(Trama{tipo, banderas, flujo, string{bloque.substr(0, cabe)}});
        bloque.remove_prefix(cabe);
        tipo = kContinuation;
        banderas = 0;
    } while (!bloque.empty());
    return tramas;
}

cero::Peticion armar(Cabeceras cabeceras, string cuerpo) {
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
            optional<cero::Peticion> primera, cero::Plazos plazos, cero::Limites limites) {
    const int descriptor = entrada.descriptor();
    sin_sigpipe(descriptor);
    plazo_de_lectura(descriptor, plazos.inactividad);
    auto comun = make_shared<Comun>(descriptor, plazos, limites);
    auto& salida = comun->salida;
    auto& ventanas = comun->ventanas;

    if (primera && !salida.crudo(kAscenso)) return;
    salida.mandar({mios.trama()});

    string preambulo(kPreambulo.size(), '\0');
    if (entrada.leer(preambulo.data(), preambulo.size()) != preambulo.size() ||
        preambulo != kPreambulo) {
        salida.mandar({Trama::goaway(0, Error::Protocolo)});
        despedirse(descriptor);
        return;
    }

    Sesion sesion{mios};
    map<uint32_t, EnCurso> en_curso;

    if (primera) {
        const auto carga = base64url(primera->cabecera("http2-settings").value_or(""));
        if (!carga || !sesion.ascender(*carga)) {
            salida.mandar({Trama::goaway(0, Error::Protocolo)});
            despedirse(descriptor);
            return;
        }
        salida.max_trama(sesion.par().max_trama);
        ventanas.reajustar(static_cast<int64_t>(sesion.par().ventana_inicial) - 65'535);
        ventanas.abrir(1);
        primera->version = "HTTP/2";
        for (const auto* de_http11 : {"connection", "upgrade", "http2-settings", "keep-alive"}) {
            primera->cabeceras.erase(de_http11);
        }
        comun->ultimo = 1;
        lanzar(comun, atender, 1, std::move(*primera));
    }

    const auto olvidar = [&](uint32_t flujo) {
        en_curso.erase(flujo);
        salida.anular(flujo);
        ventanas.soltar(flujo);
    };

    const auto cuerpo_de_mas = [&](uint32_t flujo) {
        const auto bloque = cabeceras_de_respuesta(413, {{"content-length", "0"}});
        auto tramas = tramas_de_cabeceras(flujo, bloque, salida.max_trama(), true);
        tramas.push_back(Trama::rst(flujo, Error::Ninguno));
        salida.mandar(tramas);
        olvidar(flujo);
        sesion.desechar(flujo);
    };

    const auto ocupada = [&] {
        const lock_guard tomado{comun->candado};
        return comun->en_vuelo > 0;
    };
    auto ociosa_desde = chrono::steady_clock::now();
    bool seguir = true;
    while (seguir && !comun->calma) {
        const auto queda = chrono::duration_cast<chrono::milliseconds>(
            ociosa_desde + plazos.inactividad - chrono::steady_clock::now());
        if (!entrada.esperar(max(queda, chrono::milliseconds{0}))) {
            if (ocupada()) {
                ociosa_desde = chrono::steady_clock::now();
                continue;
            }
            salida.mandar({Trama::goaway(sesion.ultimo(), Error::Ninguno)});
            break;
        }
        const auto t = leer_trama(entrada, sesion.mios().max_trama);
        if (!t) {
            if (t.error().codigo != Error::Ninguno) {
                salida.mandar({Trama::goaway(sesion.ultimo(), t.error().codigo)});
            }
            break;
        }
        if (comun->calma) break;
        if (t->tipo == kHeaders || t->tipo == kContinuation || t->tipo == kData) {
            ociosa_desde = chrono::steady_clock::now();
        }

        for (const auto flujo : salida.recoger()) {
            sesion.respondido(flujo);
            ventanas.soltar(flujo);
        }

        auto d = sesion.recibir(*t);
        comun->ultimo = sesion.ultimo();
        if (!d) {
            salida.mandar({Trama::goaway(sesion.ultimo(), d.error().codigo)});
            break;
        }
        if (t->tipo == kSettings) salida.max_trama(sesion.par().max_trama);
        if (d->cortado) olvidar(d->cortado->flujo);

        const auto desbordada = [&] {
            salida.mandar({Trama::goaway(sesion.ultimo(), Error::ControlDeFlujo)});
            seguir = false;
        };

        for (auto& accion : d->acciones) {
            if (!seguir) break;
            visit(
                [&](auto& a) {
                    using A = decay_t<decltype(a)>;
                    if constexpr (is_same_v<A, Escribir>) {
                        salida.mandar({a.trama});
                    } else if constexpr (is_same_v<A, Anulado>) {
                        olvidar(a.flujo);
                    } else if constexpr (is_same_v<A, Credito>) {
                        if (ventanas.ampliar(a.flujo, a.cuanto)) return;
                        if (a.flujo == 0) return desbordada();
                        salida.mandar({Trama::rst(a.flujo, Error::ControlDeFlujo)});
                        olvidar(a.flujo);
                        sesion.desechar(a.flujo);
                    } else if constexpr (is_same_v<A, Reajustar>) {
                        if (!ventanas.reajustar(a.delta)) desbordada();
                    } else if constexpr (is_same_v<A, Peticion>) {
                        if (anuncia_de_mas(a.cabeceras, limites.cuerpo)) return cuerpo_de_mas(a.flujo);
                        ventanas.abrir(a.flujo);
                        en_curso[a.flujo] = EnCurso{std::move(a.cabeceras), {}};
                    } else if constexpr (is_same_v<A, Cuerpo>) {
                        const auto i = en_curso.find(a.flujo);
                        if (i == en_curso.end()) return;
                        if (a.datos.size() > limites.cuerpo - i->second.cuerpo.size()) {
                            return cuerpo_de_mas(a.flujo);
                        }
                        i->second.cuerpo += a.datos;
                    }
                    uint32_t flujo = 0;
                    bool fin = false;
                    if constexpr (is_same_v<A, Peticion> || is_same_v<A, Cuerpo>) {
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

    ventanas.cerrar();
    {
        unique_lock tomado{comun->candado};
        comun->avisa.wait(tomado, [&] { return comun->en_vuelo == 0; });
    }
    despedirse(descriptor);
}

}
