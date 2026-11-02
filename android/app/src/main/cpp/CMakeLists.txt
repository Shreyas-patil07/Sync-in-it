cmake_minimum_required(VERSION 3.22.1)
project(syncinit_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

get_filename_component(REPOSITORY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../" ABSOLUTE)
set(CORE_ROOT "${REPOSITORY_ROOT}/core")

add_library(syncinit_jni SHARED
    native_bridge.cpp
    "${CORE_ROOT}/src/estimator.cpp"
    "${CORE_ROOT}/src/protocol.cpp"
    "${CORE_ROOT}/src/messages.cpp"
    "${CORE_ROOT}/src/session.cpp"
    "${CORE_ROOT}/src/transport.cpp"
)

target_include_directories(syncinit_jni PRIVATE "${CORE_ROOT}/include")
target_compile_options(syncinit_jni PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Wconversion
    -Wsign-conversion
)
