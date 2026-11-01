#include <netinet/in.h>
#include <signal.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <system_error>
#include <thread>
#include <utility>

#include "cero/formato.hpp"
#include "cero/http2/conexion.hpp"
#include "cero/http2/hpack.hpp"
#include "cero/http2/trama.hpp"
#include "cero/peticion.hpp"
#include "cero/respuesta.hpp"
#include "cero/ruta.hpp"
#include "cero/servidor.hpp"
#include "cero/socket.hpp"
#include "cliente_h2.hpp"
#include "prueba.hpp"

namespace {

using namespace std::chrono_literals;

unsigned short base() { return static_cast<unsigned short>(30'000 + ::getpid() % 15'000); }

cero::Servidor servidor_grande() {
    cero::Router r;
    (void)r.ruta("GET", "/", "grande");
    return cero::Servidor{std::move(r)}.accion(
        "grande", [](const cero::Contexto&) { return cero::Respuesta::texto(std::string(8 << 20, 'x')); });
}

int conectar_una_vez(unsigned short puerto) {
    const int d = ::socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons(puerto);
    if (::connect(d, reinterpret_cast<sockaddr*>(&a), sizeof a) == 0) return d;
    ::close(d);
    return -1;
}

int conectar(unsigned short puerto) {
    for (int intento = 0; intento < 100; ++intento) {
        if (const int d = conectar_una_vez(puerto); d >= 0) return d;
        std::this_thread::sleep_for(50ms);
    }
    return -1;
}

void dormir_siempre() {
    while (true) std::this_thread::sleep_for(1h);
}

PRUEBA(sin_hilos_se_cierra_esa_conexion_y_el_proceso_sigue) {
    const auto puerto = static_cast<unsigned short>(base() + 15'000);
    int tubo[2];
    if (::pipe(tubo) != 0) {
        COMPRUEBA(false, "no se pudo abrir la tubería");
        return;
    }
    const pid_t hijo = ::fork();
    if (hijo == 0) {
        ::close(tubo[0]);
        const rlimit uno{1, 1};
        ::setrlimit(RLIMIT_NPROC, &uno);
        bool agotado = false;
        try {
            for (int i = 0; i < 20'000; ++i) std::thread{dormir_siempre}.detach();
        } catch (const std::system_error&) {
            agotado = true;
        }
        const char aviso = agotado ? '1' : '0';
        (void)::write(tubo[1], &aviso, 1);
        if (!agotado) ::_exit(0);
        (void)servidor_grande().escuchar(puerto);
        ::_exit(1);
    }
    ::close(tubo[1]);
    char aviso = '0';
    (void)::read(tubo[0], &aviso, 1);
    ::close(tubo[0]);
    if (aviso != '1') {
        cero::linea("  · aquí no se pueden agotar los hilos; la prueba no dice nada");
        ::waitpid(hijo, nullptr, 0);
        return;
    }
    const int d = conectar(puerto);
    COMPRUEBA(d >= 0, "el hijo escucha");
    if (d >= 0) {
        timeval espera{};
        espera.tv_sec = 5;
        ::setsockopt(d, SOL_SOCKET, SO_RCVTIMEO, &espera, sizeof espera);
        const std::string_view pedido = "GET / HTTP/1.1\r\nHost: a\r\n\r\n";
        (void)::write(d, pedido.data(), pedido.size());
        char c = 0;
        const auto leidos = ::read(d, &c, 1);
        const bool cerrada = leidos == 0 || (leidos < 0 && errno == ECONNRESET);
        COMPRUEBA(cerrada, "sin hilo para atenderla, la conexión se cierra y no se queda colgada");
        ::close(d);
    }
    std::this_thread::sleep_for(200ms);
    int como = 0;
    const bool vivo = ::waitpid(hijo, &como, WNOHANG) == 0;
    COMPRUEBA(vivo, "un hilo que no se puede crear no tumba el proceso");
    ::kill(hijo, SIGKILL);
    if (vivo) ::waitpid(hijo, nullptr, 0);
}

std::string respuesta(prueba::Cliente& c) {
    std::string cabeza;
    for (auto l = c.linea(); !l.empty() && l != "\r"; l = c.linea()) cabeza += l + "\n";
    return cabeza + c.linea();
}

PRUEBA(http_024_una_conexion_ociosa_entre_peticiones_se_cierra) {
    const auto puerto = static_cast<unsigned short>(base() + 1);
    std::thread{[puerto] {
        cero::Router r;
        (void)r.ruta("GET", "/", "hola");
        (void)cero::Servidor{std::move(r)}
            .accion("hola", [](const cero::Contexto&) { return cero::Respuesta::texto("hola\n"); })
            .plazos({.sin_avance = 30s, .escritura = 30s, .inactividad = 400ms})
            .escuchar(puerto);
    }}.detach();
    prueba::Cliente c{conectar(puerto)};
    c.mandar("GET / HTTP/1.1\r\nHost: a\r\n\r\n");
    const auto primera = respuesta(c);
    COMPRUEBA(primera.starts_with("HTTP/1.1 200") && !primera.contains("Connection: close"),
              "HTTP-024: la primera petición se atiende y la conexión sigue");
    std::this_thread::sleep_for(200ms);
    c.mandar("GET / HTTP/1.1\r\nHost: a\r\n\r\n");
    COMPRUEBA(respuesta(c).starts_with("HTTP/1.1 200"),
              "HTTP-024: una segunda a media inactividad se atiende por la misma conexión");
    const auto empezo = std::chrono::steady_clock::now();
    const auto resto = c.linea();
    const auto tardo = std::chrono::steady_clock::now() - empezo;
    COMPRUEBA(resto.empty() && tardo < 2s, "HTTP-024: callar después cierra la conexión");
    COMPRUEBA(tardo >= 300ms, "HTTP-024: y no antes de la inactividad");
}

PRUEBA(http_025_un_plazo_de_cero_o_negativo_no_arranca) {
    const auto puerto = static_cast<unsigned short>(base() + 2);
    const auto arranca = [&](cero::Plazos p) {
        return cero::Servidor{cero::Router{}}.plazos(p).escuchar(puerto) == 0;
    };
    for (const auto malo : {0ms, -1ms}) {
        COMPRUEBA(!arranca({.sin_avance = malo}), "HTTP-025: sin_avance");
        COMPRUEBA(!arranca({.escritura = malo}), "HTTP-025: escritura");
        COMPRUEBA(!arranca({.inactividad = malo}), "HTTP-025: inactividad");
    }
    const auto con_topes = [&](cero::Limites l) {
        return cero::Servidor{cero::Router{}}.limites(l).escuchar(puerto) == 0;
    };
    COMPRUEBA(!con_topes({.en_vuelo = 0}), "HTTP-025: el presupuesto de respuestas en vuelo");
    COMPRUEBA(!con_topes({.cuerpo = 0}), "HTTP-025: el tope del cuerpo");
    COMPRUEBA(conectar_una_vez(puerto) < 0, "HTTP-025: y nadie llegó a escuchar");
}

PRUEBA(un_cliente_que_corta_a_mitad_no_tumba_el_proceso) {
    const auto puerto = base();
    std::thread{[puerto] { (void)servidor_grande().escuchar(puerto); }}.detach();
    const std::string_view pedido = "GET / HTTP/1.1\r\nHost: a\r\n\r\n";
    for (int vuelta = 0; vuelta < 20; ++vuelta) {
        const int d = conectar(puerto);
        if (d < 0) break;
        (void)::write(d, pedido.data(), pedido.size());
        if (vuelta % 2 == 1) {
            char algo[1024];
            (void)::read(d, algo, sizeof algo);
            const linger cortar{1, 0};
            ::setsockopt(d, SOL_SOCKET, SO_LINGER, &cortar, sizeof cortar);
        }
        ::close(d);
    }
    std::this_thread::sleep_for(500ms);
    prueba::Cliente c{conectar(puerto)};
    c.mandar(pedido);
    COMPRUEBA(c.linea().starts_with("HTTP/1.1 200"), "SIGPIPE: el proceso sigue y sigue atendiendo");
}

struct EnMarcha {
    int cliente;
    std::thread hilo;
    std::shared_ptr<std::atomic<bool>> acabo;
};

void par_tcp(int fd[2]) {
    const int oyente = ::socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    auto largo = static_cast<socklen_t>(sizeof a);
    ::bind(oyente, reinterpret_cast<sockaddr*>(&a), largo);
    ::listen(oyente, 1);
    ::getsockname(oyente, reinterpret_cast<sockaddr*>(&a), &largo);
    fd[1] = ::socket(AF_INET, SOCK_STREAM, 0);
    const int poco = 4096;
    ::setsockopt(fd[1], SOL_SOCKET, SO_RCVBUF, &poco, sizeof poco);
    ::connect(fd[1], reinterpret_cast<sockaddr*>(&a), largo);
    fd[0] = ::accept(oyente, nullptr, nullptr);
    ::setsockopt(fd[0], SOL_SOCKET, SO_SNDBUF, &poco, sizeof poco);
    ::close(oyente);
}

EnMarcha en_marcha(cero::Plazos plazos, cero::http2::Atender atender, bool tcp = false,
                   cero::Limites limites = {}) {
    int fd[2];
    if (tcp) {
        par_tcp(fd);
    } else {
        ::socketpair(AF_UNIX, SOCK_STREAM, 0, fd);
    }
    auto acabo = std::make_shared<std::atomic<bool>>(false);
    std::thread hilo{[d = fd[0], acabo, plazos, limites, atender = std::move(atender)] {
        cero::http2::DesdeSocket entrada{d};
        cero::http2::servir(entrada, cero::http2::Ajustes{}, atender, std::nullopt, plazos, limites);
        *acabo = true;
        ::close(d);
    }};
    return {fd[1], std::move(hilo), acabo};
}

cero::http2::Atender grande() {
    return [](const cero::Peticion&) { return cero::Respuesta::texto(std::string(4 << 20, 'x')); };
}

void pedir(prueba::Cliente& c, std::uint32_t id) {
    c.mandar(cero::http2::Trama{cero::http2::kHeaders,
                                cero::http2::kFinCabeceras | cero::http2::kFinFlujo, id,
                                cero::http2::codificar(prueba::get())});
}

void sin_freno(prueba::Cliente& c) {
    c.abrir_h2(prueba::ajuste(0x4, 0x7fff'ffff));
    c.mandar(cero::http2::Trama::ventana(0, 0x7fff'ffff - 65'535));
}

bool esperar(const std::atomic<bool>& hecho, std::chrono::milliseconds cuanto) {
    const auto hasta = std::chrono::steady_clock::now() + cuanto;
    while (!hecho && std::chrono::steady_clock::now() < hasta) std::this_thread::sleep_for(20ms);
    return hecho;
}

PRUEBA(un_cliente_que_no_lee_no_cuelga_la_conexion) {
    auto [d, hilo, acabo] = en_marcha({.sin_avance = 200ms, .escritura = 2s, .inactividad = 30s},
                                      grande());
    {
        prueba::Cliente c{d};
        sin_freno(c);
        for (std::uint32_t id = 1; id < 8; id += 2) pedir(c, id);
        COMPRUEBA(esperar(*acabo, 3s), "una escritura que no avanza tiene plazo y suelta la conexión");
    }
    hilo.join();
}

PRUEBA(un_cliente_que_lee_a_gotas_no_retiene_la_conexion) {
    auto [d, hilo, acabo] = en_marcha({.sin_avance = 1s, .escritura = 1s, .inactividad = 30s},
                                      grande());
    {
        prueba::Cliente c{d};
        sin_freno(c);
        pedir(c, 1);
        const auto hasta = std::chrono::steady_clock::now() + 8s;
        char gota[1024];
        while (!*acabo && std::chrono::steady_clock::now() < hasta) {
            (void)::read(d, gota, sizeof gota);
            std::this_thread::sleep_for(300ms);
        }
        COMPRUEBA(acabo->load(),
                  "leer a gotas reinicia el plazo de cada send, pero no el plazo total de la escritura");
    }
    hilo.join();
}

PRUEBA(h2_052_un_cliente_callado_con_la_ventana_a_cero_suelta_sus_flujos) {
    std::atomic<int> llamadas{0};
    auto [d, hilo, acabo] = en_marcha({.sin_avance = 30s, .escritura = 300ms, .inactividad = 300ms},
                                      [&](const cero::Peticion&) {
                                          ++llamadas;
                                          return cero::Respuesta::texto("hola");
                                      });
    {
        prueba::Cliente c{d};
        c.abrir_h2(prueba::ajuste(0x4, 0));
        pedir(c, 1);
        pedir(c, 3);
        const auto empezo = std::chrono::steady_clock::now();
        int cortados = 0;
        std::optional<cero::http2::Trama> adios;
        for (auto x = c.trama(); x && !adios; x = c.trama()) {
            if (x->tipo == cero::http2::kRstStream &&
                cero::http2::entero32(x->carga, 0) == static_cast<std::uint32_t>(cero::http2::Error::Anulado)) {
                ++cortados;
            }
            if (x->tipo == cero::http2::kGoaway) adios = x;
        }
        const auto tardo = std::chrono::steady_clock::now() - empezo;
        COMPRUEBA(cortados == 2, "H2-052: los dos flujos sin crédito se cortan con CANCEL");
        COMPRUEBA(adios && cero::http2::entero32(adios->carga, 4) == 0,
                  "H2-053: y después el silencio se despide con GOAWAY NO_ERROR");
        COMPRUEBA(tardo < 2s, "dentro de los dos plazos");
        COMPRUEBA(esperar(*acabo, 2s), "y los hilos que esperaban ventana terminan");
        COMPRUEBA(llamadas == 2, "los dos flujos se habían atendido");
    }
    hilo.join();
}

PRUEBA(h2_052_el_goteo_de_credito_no_reinicia_el_plazo) {
    auto [d, hilo, acabo] = en_marcha({.sin_avance = 30s, .escritura = 500ms, .inactividad = 30s},
                                      [](const cero::Peticion&) {
                                          return cero::Respuesta::texto(std::string(100, 'x'));
                                      });
    {
        prueba::Cliente c{d};
        c.abrir_h2(prueba::ajuste(0x4, 0));
        pedir(c, 1);
        std::atomic<bool> parar{false};
        std::thread goteo{[&] {
            while (!parar) {
                c.mandar(cero::http2::Trama::ventana(1, 1));
                std::this_thread::sleep_for(50ms);
            }
        }};
        std::size_t datos = 0;
        std::optional<cero::http2::Trama> corte;
        for (auto x = c.trama(); x && !corte; x = c.trama()) {
            if (x->tipo == cero::http2::kData && x->flujo == 1) datos += x->carga.size();
            if (x->tipo == cero::http2::kRstStream && x->flujo == 1) corte = x;
        }
        parar = true;
        goteo.join();
        COMPRUEBA(corte && cero::http2::entero32(corte->carga, 0) ==
                               static_cast<std::uint32_t>(cero::http2::Error::Anulado),
                  "H2-052: un octeto de crédito cada 50 ms no salva una trama en 500 ms");
        COMPRUEBA(datos > 0 && datos < 100, "H2-052: salió lo que cabía, y no la respuesta entera");
    }
    hilo.join();
}

PRUEBA(h2_053_los_ping_no_reinician_la_inactividad) {
    auto [d, hilo, acabo] = en_marcha({.sin_avance = 30s, .escritura = 30s, .inactividad = 300ms},
                                      [](const cero::Peticion&) { return cero::Respuesta::texto("hola"); });
    {
        prueba::Cliente c{d};
        c.abrir_h2();
        std::atomic<bool> parar{false};
        std::thread latido{[&] {
            while (!parar) {
                c.mandar(cero::http2::Trama{cero::http2::kPing, 0, 0, "12345678"});
                c.mandar(cero::http2::Trama::ventana(0, 1));
                c.mandar(cero::http2::Trama{cero::http2::kSettings, 0, 0, ""});
                c.mandar(cero::http2::Trama{cero::http2::kPriority, 0, 3, std::string{"\0\0\0\0\x0f", 5}});
                std::this_thread::sleep_for(100ms);
            }
        }};
        const auto empezo = std::chrono::steady_clock::now();
        const auto adios = c.hasta(cero::http2::kGoaway);
        const auto tardo = std::chrono::steady_clock::now() - empezo;
        parar = true;
        latido.join();
        COMPRUEBA(adios && cero::http2::entero32(adios->carga, 4) == 0,
                  "H2-053: PING, WINDOW_UPDATE, SETTINGS y PRIORITY no son trabajo, y la conexión se despide");
        COMPRUEBA(tardo < 2s, "H2-053: dentro del plazo de inactividad");
        COMPRUEBA(tardo >= 225ms, "H2-053: y no antes");
    }
    hilo.join();
}

PRUEBA(h2_054_las_respuestas_en_vuelo_tienen_presupuesto) {
    auto [d, hilo, acabo] = en_marcha({.sin_avance = 30s, .escritura = 30s, .inactividad = 30s},
                                      grande());
    {
        prueba::Cliente c{d};
        c.abrir_h2(prueba::ajuste(0x4, 0));
        for (std::uint32_t id = 1; id < 20; id += 2) pedir(c, id);
        const auto adios = c.hasta(cero::http2::kGoaway);
        COMPRUEBA(adios && cero::http2::entero32(adios->carga, 4) ==
                               static_cast<std::uint32_t>(cero::http2::Error::Calma),
                  "H2-054: 40 MiB construidos y sin salir pasan de los 32: ENHANCE_YOUR_CALM");
        COMPRUEBA(!c.trama(), "H2-054: y el servidor cierra su mitad");
    }
    COMPRUEBA(esperar(*acabo, 2s), "H2-054: la conexión suelta la memoria y los hilos");
    hilo.join();
}

PRUEBA(h2_054_una_respuesta_sola_pasa_aunque_supere_el_presupuesto) {
    auto [d, hilo, acabo] = en_marcha({.sin_avance = 30s, .escritura = 30s, .inactividad = 30s},
                                      grande(), false, {.en_vuelo = 1 << 20});
    {
        prueba::Cliente c{d};
        sin_freno(c);
        pedir(c, 1);
        std::size_t datos = 0;
        bool fin = false;
        for (auto x = c.trama(); x && !fin; x = c.trama()) {
            COMPRUEBA(x->tipo != cero::http2::kGoaway, "H2-054: sin GOAWAY");
            if (x->tipo == cero::http2::kData) datos += x->carga.size();
            fin = x->tipo == cero::http2::kData && x->fin_flujo();
        }
        COMPRUEBA(fin && datos == std::size_t{4} << 20,
                  "H2-054: con nada más en vuelo, 4 MiB salen enteros con un presupuesto de 1 MiB");
    }
    hilo.join();
}

PRUEBA(h2_055_un_cuerpo_de_mas_recibe_413) {
    auto [d, hilo, acabo] = en_marcha({.sin_avance = 30s, .escritura = 30s, .inactividad = 30s},
                                      [](const cero::Peticion& p) { return cero::Respuesta::texto(p.cuerpo); },
                                      false, {.cuerpo = 100});
    {
        prueba::Cliente c{d};
        c.abrir_h2();
        auto post = prueba::get();
        post[0].second = "POST";
        c.mandar(cero::http2::Trama{cero::http2::kHeaders, cero::http2::kFinCabeceras, 1,
                                    cero::http2::codificar(post)});
        c.mandar(cero::http2::Trama{cero::http2::kData, 0, 1, std::string(60, 'x')});
        c.mandar(cero::http2::Trama{cero::http2::kData, 0, 1, std::string(60, 'x')});
        const auto r = c.hasta(cero::http2::kHeaders);
        COMPRUEBA(r && r->flujo == 1 && prueba::estado(*r) == "413" && r->fin_flujo(),
                  "H2-055: pasar de 100 octetos de cuerpo es 413 y fin de flujo");
        const auto corte = c.hasta(cero::http2::kRstStream);
        COMPRUEBA(corte && corte->flujo == 1 && cero::http2::entero32(corte->carga, 0) == 0,
                  "H2-055: y RST_STREAM NO_ERROR para que deje de mandar");

        auto anunciada = post;
        anunciada.emplace_back("content-length", "1000");
        c.mandar(cero::http2::Trama{cero::http2::kHeaders, cero::http2::kFinCabeceras, 3,
                                    cero::http2::codificar(anunciada)});
        const auto antes = c.hasta(cero::http2::kHeaders);
        COMPRUEBA(antes && antes->flujo == 3 && prueba::estado(*antes) == "413",
                  "H2-055: un content-length de más es 413 antes de mandar nada");
    }
    hilo.join();
}

PRUEBA(un_manejador_lento_no_es_un_cliente_callado) {
    auto [d, hilo, acabo] = en_marcha({.sin_avance = 30s, .escritura = 30s, .inactividad = 200ms},
                                      [](const cero::Peticion&) {
                                          std::this_thread::sleep_for(700ms);
                                          return cero::Respuesta::texto("tarde");
                                      });
    {
        prueba::Cliente c{d};
        c.abrir_h2();
        pedir(c, 1);
        const auto r = c.hasta(cero::http2::kHeaders);
        COMPRUEBA(r && prueba::estado(*r) == "200", "mientras se calcula, callar no es inactividad");
    }
    hilo.join();
}

}

PRUEBAS_MAIN
