#include <netinet/in.h>
#include <signal.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdint>
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

int conectar(unsigned short puerto) {
    for (int intento = 0; intento < 100; ++intento) {
        const int d = ::socket(AF_INET, SOCK_STREAM, 0);
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        a.sin_port = htons(puerto);
        if (::connect(d, reinterpret_cast<sockaddr*>(&a), sizeof a) == 0) return d;
        ::close(d);
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
        COMPRUEBA(leidos <= 0, "sin hilo para atenderla, la conexión se cierra");
        ::close(d);
    }
    std::this_thread::sleep_for(200ms);
    int como = 0;
    const bool vivo = ::waitpid(hijo, &como, WNOHANG) == 0;
    COMPRUEBA(vivo, "un hilo que no se puede crear no tumba el proceso");
    ::kill(hijo, SIGKILL);
    if (vivo) ::waitpid(hijo, nullptr, 0);
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

PRUEBA(un_cliente_que_no_lee_no_cuelga_la_conexion) {
    int fd[2];
    ::socketpair(AF_UNIX, SOCK_STREAM, 0, fd);
    timeval espera{};
    espera.tv_usec = 200'000;
    ::setsockopt(fd[0], SOL_SOCKET, SO_SNDTIMEO, &espera, sizeof espera);
    std::atomic<bool> acabo{false};
    std::thread hilo{[d = fd[0], &acabo] {
        cero::http2::DesdeSocket entrada{d};
        cero::http2::servir(entrada, cero::http2::Ajustes{}, [](const cero::Peticion&) {
            return cero::Respuesta::texto(std::string(4 << 20, 'x'));
        });
        acabo = true;
        ::close(d);
    }};
    {
        prueba::Cliente c{fd[1]};
        c.abrir_h2(prueba::ajuste(0x4, 0x7fff'ffff));
        c.mandar(cero::http2::Trama::ventana(0, 0x7fff'ffff - 65'535));
        for (std::uint32_t id = 1; id < 8; id += 2) {
            c.mandar(cero::http2::Trama{cero::http2::kHeaders,
                                        cero::http2::kFinCabeceras | cero::http2::kFinFlujo, id,
                                        cero::http2::codificar(prueba::get())});
        }
        for (int i = 0; i < 60 && !acabo; ++i) std::this_thread::sleep_for(50ms);
        COMPRUEBA(acabo.load(), "una escritura que no avanza tiene plazo y suelta la conexión");
    }
    hilo.join();
}

}

PRUEBAS_MAIN
