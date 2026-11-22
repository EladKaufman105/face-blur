cmake_minimum_required(VERSION 3.22.1)
project(faceblur_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if (NOT EXISTS ${WHISPER_DIR}/include/whisper.h)
    message(FATAL_ERROR "whisper.cpp missing: run ci/fetch-whisper.sh")
endif()

# whisper.cpp + ggml, CPU only, static, built for the target (not the build machine).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Every recent phone (vivo X300 Pro: ARMv9.2) has dot product and fp16 arithmetic.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif (ANDROID_ABI STREQUAL "x86_64")
    # The x86_64 Android ABI includes SSE4.2 (only the CI emulator runs this).
    set(GGML_SSE42 ON CACHE BOOL "" FORCE)
endif()
# Speech recognition is unusable without optimization, so optimize debug builds too.
add_compile_options(-O3)
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_definitions(NDEBUG)
endif()
add_subdirectory(${WHISPER_DIR} whisper)

add_library(faceblur_whisper SHARED whisper_jni.cpp)
target_link_libraries(faceblur_whisper PRIVATE whisper log)
# 16 KB pages (Android 15+ devices may use them).
target_link_options(faceblur_whisper PRIVATE "-Wl,-z,max-page-size=16384")

# MP3 encoding ("video to MP3"): LAME 3.100's encoder (LGPL, sources in lame/).
file(GLOB LAME_SRC ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame/*.c)
add_library(onit_mp3 SHARED mp3_jni.c ${LAME_SRC})
target_include_directories(onit_mp3 PRIVATE lame/include lame/libmp3lame)
# No autoconf here: the few things its config.h would say.
target_compile_definitions(onit_mp3 PRIVATE STDC_HEADERS=1 HAVE_STDINT_H=1 HAVE_LIMITS_H=1 HAVE_STRCHR=1 HAVE_MEMCPY=1 ieee754_float32_t=float)
target_compile_options(onit_mp3 PRIVATE -w)
target_link_libraries(onit_mp3 PRIVATE m)
target_link_options(onit_mp3 PRIVATE "-Wl,-z,max-page-size=16384")
