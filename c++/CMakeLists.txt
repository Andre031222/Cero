# CMake es a C++ lo que Maven a Java y Cargo a Rust: una herramienta de construcción, no una
# dependencia de ejecución. Lo que se enlaza es la biblioteca estándar y nada más.
cmake_minimum_required(VERSION 3.28)
project(cero-cpp CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
add_compile_options(-Wall -Wextra -Wpedantic)

add_library(cero-http
    cero-http/src/observabilidad.cpp
    cero-http/src/seguridad.cpp
    cero-http/src/sesion.cpp
    cero-http/src/peticion.cpp
    cero-http/src/respuesta.cpp
    cero-http/src/ruta.cpp
    cero-http/src/servidor.cpp)
target_include_directories(cero-http PUBLIC cero-http/include)
find_package(Threads REQUIRED)
target_link_libraries(cero-http PUBLIC Threads::Threads)

add_executable(conforme cero-http/src/conforme.cpp)
target_link_libraries(conforme PRIVATE cero-http)

enable_testing()
foreach(suite conformidad observabilidad ruteo seguridad sesiones)
    add_executable(pruebas-${suite} pruebas/${suite}.cpp)
    target_include_directories(pruebas-${suite} PRIVATE pruebas)
    target_link_libraries(pruebas-${suite} PRIVATE cero-http)
    add_test(NAME ${suite} COMMAND pruebas-${suite})
endforeach()
