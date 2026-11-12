#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <optional>
#include <string>
#include <string_view>
#include <thread>

#include "cero/json.hpp"
#include "cero/servidor.hpp"
#include "cero/socket.hpp"
#include "prueba.hpp"

using namespace std;
using namespace cero;

namespace {

Peticion peticion(string_view metodo, string_view destino, string_view cuerpo = {}) {
    Peticion p;
    p.metodo = metodo;
    p.destino = destino;
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("host", "x");
    p.cuerpo = cuerpo;
    return p;
}

sockaddr_in local(unsigned short puerto) {
    sockaddr_in d{};
    d.sin_family = AF_INET;
    d.sin_port = htons(puerto);
    d.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    return d;
}

unsigned short puerto_libre() {
    const int s = socket(AF_INET, SOCK_STREAM, 0);
    auto d = local(0);
    enlazar(s, reinterpret_cast<sockaddr*>(&d), sizeof d);
    socklen_t largo = sizeof d;
    getsockname(s, reinterpret_cast<sockaddr*>(&d), &largo);
    close(s);
    return ntohs(d.sin_port);
}

string por_socket(unsigned short puerto, string_view pedido) {
    for (int intento = 0; intento < 100; ++intento) {
        const int s = socket(AF_INET, SOCK_STREAM, 0);
        auto d = local(puerto);
        if (connect(s, reinterpret_cast<sockaddr*>(&d), sizeof d) == 0) {
            send(s, pedido.data(), pedido.size(), 0);
            string todo;
            char bufer[4096];
            for (ssize_t n; (n = recv(s, bufer, sizeof bufer, 0)) > 0;) todo.append(bufer, n);
            close(s);
            return todo;
        }
        close(s);
        this_thread::sleep_for(chrono::milliseconds{50});
    }
    return {};
}

Servidor ejemplo() {
    return Servidor{}
        .get("/", [](const Contexto&) { return Json::objeto({{"hola", "mundo"}}); })
        .get("/saludo/{nombre}",
             [](const Contexto& c) {
                 return Json::objeto({{"saludo", "hola, " + string{*c.variable("nombre")}}});
             })
        .post("/eco", [](const Contexto& c) { return c.cuerpo_json(); })
        .put("/x", [](const Contexto&) { return "put"; })
        .delete_("/x", [](const Contexto&) { return "delete"; })
        .patch("/x", [](const Contexto&) { return "patch"; });
}

}

PRUEBA(get_registra_ruta_y_accion_en_una_llamada) {
    const auto s = ejemplo();
    const auto r = s.responder(peticion("GET", "/"));
    COMPRUEBA(r.estado == 200, "GET / da 200");
    COMPRUEBA(r.cuerpo == R"({"hola":"mundo"})", "GET / da el objeto");
}

PRUEBA(la_variable_llega_a_la_accion) {
    const auto r = ejemplo().responder(peticion("GET", "/saludo/ana"));
    COMPRUEBA(r.cuerpo == R"({"saludo":"hola, ana"})", "el saludo lleva el nombre");
}

PRUEBA(post_devuelve_el_json_recibido) {
    const auto r = ejemplo().responder(peticion("POST", "/eco", R"({"a":[1,true]})"));
    COMPRUEBA(r.estado == 200, "POST /eco da 200");
    COMPRUEBA(r.cuerpo == R"({"a":[1,true]})", "el eco devuelve el mismo JSON");
}

PRUEBA(los_demas_verbos_tambien) {
    const auto s = ejemplo();
    COMPRUEBA(s.responder(peticion("PUT", "/x")).cuerpo == "put", "PUT");
    COMPRUEBA(s.responder(peticion("DELETE", "/x")).cuerpo == "delete", "DELETE");
    COMPRUEBA(s.responder(peticion("PATCH", "/x")).cuerpo == "patch", "PATCH");
}

PRUEBA(otro_verbo_sobre_el_mismo_camino_sigue_siendo_405) {
    const auto r = ejemplo().responder(peticion("DELETE", "/eco"));
    COMPRUEBA(r.estado == 405, "DELETE /eco da 405");
}

PRUEBA(convive_con_ruta_y_accion) {
    Router router;
    COMPRUEBA(router.ruta("GET", "/viejo", "viejo").has_value(), "la ruta se registra");
    const auto s = Servidor{std::move(router)}
                       .accion("viejo", [](const Contexto&) { return "viejo"; })
                       .get("/nuevo", [](const Contexto&) { return "nuevo"; });
    COMPRUEBA(s.responder(peticion("GET", "/viejo")).cuerpo == "viejo", "la forma larga sigue");
    COMPRUEBA(s.responder(peticion("GET", "/nuevo")).cuerpo == "nuevo", "la corta también");
}

PRUEBA(un_patron_mal_formado_hace_fallar_escuchar) {
    const auto s = Servidor{}
                       .get("/a/{", [](const Contexto&) { return "nunca"; })
                       .direccion("127.0.0.1");
    COMPRUEBA(s.escuchar(0) == 1, "escuchar devuelve 1 sin abrir el puerto");
}

PRUEBA(un_patron_mal_formado_da_500_en_responder) {
    const auto s = Servidor{}
                       .get("/bien", [](const Contexto&) { return "bien"; })
                       .get("/a/{", [](const Contexto&) { return "nunca"; });
    const auto r = s.responder(peticion("GET", "/bien"));
    COMPRUEBA(r.estado == 500, "con un patrón malo todo da 500");
    COMPRUEBA(r.cuerpo.find("/a/{") != string::npos, "el cuerpo nombra el patrón malo");
}

PRUEBA(head_sobre_un_get_corto_da_200_sin_cuerpo) {
    const auto puerto = puerto_libre();
    thread{[puerto] { (void)ejemplo().direccion("127.0.0.1").escuchar(puerto); }}.detach();
    const auto r = por_socket(puerto, "HEAD / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
    COMPRUEBA(r.starts_with("HTTP/1.1 200"), "HEAD / da 200");
    COMPRUEBA(r.ends_with("\r\n\r\n"), "HEAD no lleva cuerpo");
}

PRUEBA(el_405_de_la_forma_corta_lleva_allow) {
    const auto r = ejemplo().responder(peticion("DELETE", "/eco"));
    optional<string> permitidos;
    for (const auto& [nombre, valor] : r.extra) {
        if (nombre == "Allow") permitidos = valor;
    }
    COMPRUEBA(permitidos == "POST", "Allow nombra POST");
}

PRUEBA(post_con_json_malo_da_400) {
    const auto r = ejemplo().responder(peticion("POST", "/eco", "{no es json"));
    COMPRUEBA(r.estado == 400, "JSON mal formado da 400");
}

PRUEBAS_MAIN
