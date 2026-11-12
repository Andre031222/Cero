#include <arpa/inet.h>
#include <netinet/in.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <format>
#include <string>
#include <string_view>
#include <thread>

using namespace std;

namespace {

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
    ::bind(s, reinterpret_cast<sockaddr*>(&d), sizeof d);
    socklen_t largo = sizeof d;
    getsockname(s, reinterpret_cast<sockaddr*>(&d), &largo);
    close(s);
    return ntohs(d.sin_port);
}

int conectar(unsigned short puerto) {
    const int s = socket(AF_INET, SOCK_STREAM, 0);
    auto d = local(puerto);
    if (connect(s, reinterpret_cast<sockaddr*>(&d), sizeof d) == 0) return s;
    close(s);
    return -1;
}

string pedir(unsigned short puerto, string_view metodo, string_view camino, string_view cuerpo = {}) {
    const int s = conectar(puerto);
    if (s < 0) return {};
    const auto peticion = format("{} {} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n"
                                 "Content-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
                                 metodo, camino, cuerpo.size(), cuerpo);
    send(s, peticion.data(), peticion.size(), 0);
    string respuesta;
    char bufer[4096];
    for (ssize_t n; (n = recv(s, bufer, sizeof bufer, 0)) > 0;) respuesta.append(bufer, n);
    close(s);
    return respuesta;
}

int fallos = 0;

void comprueba(const string& respuesta, string_view cuerpo, string_view que) {
    const auto corte = respuesta.find("\r\n\r\n");
    const bool bien = respuesta.starts_with("HTTP/1.1 200") && corte != string::npos &&
                      string_view{respuesta}.substr(corte + 4) == cuerpo;
    puts(format("  {} {}", bien ? "ok" : "x", que).c_str());
    if (!bien) ++fallos;
}

}

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    const auto puerto = puerto_libre();
    const pid_t hijo = fork();
    if (hijo == 0) {
        setenv("PUERTO", to_string(puerto).c_str(), 1);
        execl(argv[1], argv[1], nullptr);
        _exit(127);
    }
    for (int i = 0; i < 100; ++i) {
        if (const int s = conectar(puerto); s >= 0) {
            close(s);
            break;
        }
        this_thread::sleep_for(chrono::milliseconds{50});
    }
    comprueba(pedir(puerto, "GET", "/"), R"({"hola":"mundo"})", "GET /");
    comprueba(pedir(puerto, "GET", "/saludo/ana"), R"({"saludo":"hola, ana"})", "GET /saludo/ana");
    comprueba(pedir(puerto, "POST", "/eco", R"({"a":[1,"b"]})"), R"({"a":[1,"b"]})", "POST /eco");
    kill(hijo, SIGTERM);
    waitpid(hijo, nullptr, 0);
    return fallos == 0 ? 0 : 1;
}
