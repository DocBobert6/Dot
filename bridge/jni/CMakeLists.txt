// Dot — bridge CMake (Phase 0 scaffolding).
//
// Builds libdot-bridge.so from bridge.cpp only, linking the
// prebuilt mGBA libretro core .so as an IMPORTED library — the same
// link the real bridge will keep.
//
// CI invocation (from the repository root):
//   cmake -S bridge/jni -B build-bridge \
//     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
//     -DANDROID_ABI=arm64-v8a \
//     -DANDROID_PLATFORM=android-21 \
//     -DMGBA_ROOT=$GITHUB_WORKSPACE/core/mgba \
//     -DMGBA_SO=$GITHUB_WORKSPACE/build-core/mgba_libretro.so \
//     -DCMAKE_BUILD_TYPE=Release
//   cmake --build build-bridge
//
// SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.10)
project(dot-bridge)

if(NOT DEFINED MGBA_ROOT OR MGBA_ROOT STREQUAL "")
    message(FATAL_ERROR "MGBA_ROOT is required: path to the cloned mgba fork (core/mgba)")
endif()
if(NOT DEFINED MGBA_SO OR MGBA_SO STREQUAL "")
    message(FATAL_ERROR "MGBA_SO is required: path to the built core library (build-core/mgba_libretro.so)")
endif()
if(NOT EXISTS "${MGBA_SO}")
    message(FATAL_ERROR "MGBA_SO does not exist: ${MGBA_SO}")
endif()

add_library(mgba_libretro SHARED IMPORTED)
set_target_properties(mgba_libretro PROPERTIES IMPORTED_LOCATION "${MGBA_SO}")

add_library(dot-bridge SHARED bridge.cpp)

target_include_directories(dot-bridge PRIVATE
    "${MGBA_ROOT}/src/platform/libretro"
)

target_link_libraries(dot-bridge
    mgba_libretro
    log
    android
)
