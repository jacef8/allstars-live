# Native SRT ingest for All-Stars Live (M1, libsrt + NDK route).
#
# Builds libsrtjni.so: a thin JNI bridge that runs the libsrt receive loop and a
# minimal MPEG-TS depacketizer, handing demuxed H.264 access units up to
# SrtVideoSource.kt -> MediaCodecVideoDecoder.
#
# USE_LIBSRT defaults OFF so the module compiles (in stub mode) BEFORE libsrt is
# vendored. Flip it ON after running the vendoring steps in README.md.
cmake_minimum_required(VERSION 3.22.1)
project(srtjni LANGUAGES C CXX)

option(USE_LIBSRT "Link the real Haivision libsrt transport" OFF)

add_library(srtjni SHARED
        srt_jni.cpp
        ts_demuxer.cpp)

target_compile_features(srtjni PRIVATE cxx_std_17)

find_library(log-lib log)
target_link_libraries(srtjni ${log-lib})

if(USE_LIBSRT)
    message(STATUS "srtjni: linking vendored libsrt for ${ANDROID_ABI}")
    target_compile_definitions(srtjni PRIVATE USE_LIBSRT=1)

    # Expectation: Haivision/srt built per-ABI under third_party/srt (see README.md).
    set(SRT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/srt)
    target_include_directories(srtjni PRIVATE
            ${SRT_ROOT}/include
            ${SRT_ROOT}/include/srt)

    # Static lib keeps the APK lean and avoids packaging extra .so files.
    add_library(srt STATIC IMPORTED)
    set_target_properties(srt PROPERTIES IMPORTED_LOCATION
            ${SRT_ROOT}/libs/${ANDROID_ABI}/libsrt.a)

    # Built with ENABLE_ENCRYPTION=OFF for the closed LAN, so no SSL backend is
    # needed. libsrt is C++ — link the NDK C++ runtime so its symbols resolve.
    target_link_libraries(srtjni srt)
else()
    message(WARNING
            "srtjni: USE_LIBSRT=OFF -> stub transport (no real SRT). See cpp/README.md.")
endif()
