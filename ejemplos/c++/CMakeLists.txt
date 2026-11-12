cmake_minimum_required(VERSION 3.28)
project(hola CXX)

include(FetchContent)
FetchContent_Declare(cero
    GIT_REPOSITORY https://github.com/Andre031222/Cero
    GIT_TAG main
    GIT_SHALLOW TRUE
    SOURCE_SUBDIR c++)
FetchContent_MakeAvailable(cero)

add_executable(hola main.cpp)
target_link_libraries(hola PRIVATE cero::http)

enable_testing()
add_executable(punta-a-punta pruebas/punta_a_punta.cpp)
target_compile_features(punta-a-punta PRIVATE cxx_std_23)
add_test(NAME punta_a_punta COMMAND punta-a-punta $<TARGET_FILE:hola>)
set_tests_properties(punta_a_punta PROPERTIES TIMEOUT 60)
