cmake_minimum_required(VERSION 3.28)
project(cero-cpp CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
add_compile_options(-Wall -Wextra -Wpedantic)

add_library(cero-http
    cero-http/src/http2/conexion.cpp
    cero-http/src/http2/ventanas.cpp
    cero-http/src/http2/flujo.cpp
    cero-http/src/http2/hpack.cpp
    cero-http/src/http2/trama.cpp
    cero-http/src/dependencias.cpp
    cero-http/src/estaticos.cpp
    cero-http/src/json.cpp
    cero-http/src/observabilidad.cpp
    cero-http/src/seguridad.cpp
    cero-http/src/sesion.cpp
    cero-http/src/peticion.cpp
    cero-http/src/respuesta.cpp
    cero-http/src/ruta.cpp
    cero-http/src/servidor.cpp
    cero-http/src/socket.cpp
    cero-http/src/validacion.cpp)
target_include_directories(cero-http PUBLIC cero-http/include)
find_package(Threads REQUIRED)
target_link_libraries(cero-http PUBLIC Threads::Threads)

add_library(cero-data cero-data/src/disco.cpp)
target_include_directories(cero-data PUBLIC cero-data/include)
target_link_libraries(cero-data PUBLIC cero-http)

add_executable(conforme cero-http/src/conforme.cpp)
target_link_libraries(conforme PRIVATE cero-http)

enable_testing()
foreach(suite conformidad dependencias despacho estaticos fallos http2_control_flujo http2_flujos http2_hpack http2_tramas json observabilidad pipeline robustez ruteo seguridad sesiones validacion)
    add_executable(pruebas-${suite} pruebas/${suite}.cpp)
    target_include_directories(pruebas-${suite} PRIVATE pruebas)
    target_link_libraries(pruebas-${suite} PRIVATE cero-http)
    add_test(NAME ${suite} COMMAND pruebas-${suite})
endforeach()
add_executable(pruebas-sesiones_disco pruebas/sesiones_disco.cpp)
target_include_directories(pruebas-sesiones_disco PRIVATE pruebas)
target_link_libraries(pruebas-sesiones_disco PRIVATE cero-data)
add_test(NAME sesiones_disco COMMAND pruebas-sesiones_disco)
