#pragma once

#include <chrono>
#include <string_view>

namespace cero {

struct Plazos {
    std::chrono::milliseconds sin_avance{30'000};
    std::chrono::milliseconds escritura{60'000};
    std::chrono::milliseconds inactividad{120'000};

    bool validos() const {
        return sin_avance.count() > 0 && escritura.count() > 0 && inactividad.count() > 0;
    }
};

void sin_sigpipe(int descriptor);
void plazo_de_lectura(int descriptor, std::chrono::milliseconds plazo);
bool mandar_todo(int descriptor, std::string_view datos, const Plazos& plazos);

}
