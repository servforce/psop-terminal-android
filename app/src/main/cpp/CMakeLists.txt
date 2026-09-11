cmake_minimum_required(VERSION 3.22.1)

project(minicpm_v46 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "MiniCPM-V 4.6 demo supports arm64-v8a only")
endif()

set(GGML_SYSTEM_ARCH "ARM")
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp-omni")
get_filename_component(LLAMA_SRC "${LLAMA_SRC}" ABSOLUTE)
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp-omni submodule missing; run git submodule update --init")
endif()

add_subdirectory("${LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama")
# LLAMA_INSTALL_VERSION is scoped to the subdirectory above. mtmd expects it
# when embedded directly, so provide the same harmless local soname version.
set(LLAMA_INSTALL_VERSION "0.0.0")
add_subdirectory(
    "${LLAMA_SRC}/tools/mtmd"
    "${CMAKE_BINARY_DIR}/mtmd"
    EXCLUDE_FROM_ALL
)

add_library(minicpm_v46 SHARED
    minicpm_v46_jni.cpp
)

target_compile_definitions(minicpm_v46 PRIVATE
    GGML_SYSTEM_ARCH=ARM
    GGML_CPU_KLEIDIAI=1
    GGML_OPENMP=1
)

target_include_directories(minicpm_v46 PRIVATE
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/ggml/src"
    "${LLAMA_SRC}/tools/mtmd"
)

target_link_libraries(minicpm_v46
    llama
    llama-common
    mtmd
    android
    log
)
