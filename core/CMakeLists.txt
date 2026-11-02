# SPDX-License-Identifier: Apache-2.0
cmake_minimum_required(VERSION 3.20)
project(sync_in_it_core VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Python3 3.11 REQUIRED COMPONENTS Interpreter)

add_library(sync_in_it_core
    src/estimator.cpp
    src/timing.cpp
)
target_include_directories(sync_in_it_core PUBLIC include)

if(MSVC)
    target_compile_options(sync_in_it_core PRIVATE /W4 /permissive-)
else()
    target_compile_options(sync_in_it_core PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion)
endif()

set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(GENERATED_VECTORS "${GENERATED_DIR}/golden_vectors.h")
set(VECTOR_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../protocol/vectors/m0_timing.json")
set(VECTOR_GENERATOR "${CMAKE_CURRENT_SOURCE_DIR}/../tools/generate_cpp_vectors.py")

add_custom_command(
    OUTPUT "${GENERATED_VECTORS}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${GENERATED_DIR}"
    COMMAND "${Python3_EXECUTABLE}" "${VECTOR_GENERATOR}"
            --input "${VECTOR_SOURCE}"
            --output "${GENERATED_VECTORS}"
    DEPENDS "${VECTOR_SOURCE}" "${VECTOR_GENERATOR}"
    VERBATIM
)
add_custom_target(generate_m0_vectors DEPENDS "${GENERATED_VECTORS}")

add_executable(sync_in_it_core_tests tests/core_tests.cpp "${GENERATED_VECTORS}")
add_dependencies(sync_in_it_core_tests generate_m0_vectors)
target_include_directories(sync_in_it_core_tests PRIVATE "${GENERATED_DIR}")
target_link_libraries(sync_in_it_core_tests PRIVATE sync_in_it_core)
if(MSVC)
    target_compile_options(sync_in_it_core_tests PRIVATE /W4 /permissive-)
else()
    target_compile_options(sync_in_it_core_tests PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion)
endif()

enable_testing()
add_test(NAME sync_in_it_core_golden_vectors COMMAND sync_in_it_core_tests)
