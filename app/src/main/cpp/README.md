# Native SRT ingest (libsrt + NDK)

This module is the chosen M1 transport: **libsrt receives the Mevo `srt://` feed,
a minimal MPEG-TS depacketizer pulls out the H.264 access units, and
`MediaCodecVideoDecoder` hardware-decodes them to the Surface.** See the
`allstars-live-srt-ingest-decision` memo for *why* this beats an FFmpeg AAR
(short version: `ffmpeg-kit` was retired in Jan 2025, so "FFmpeg AAR" now means
building all of FFmpeg from source — strictly more work than libsrt alone).

## Files

| File | Role |
|------|------|
| `CMakeLists.txt` | Builds `libsrtjni.so`. `USE_LIBSRT` toggles real vs stub transport. |
| `srt_jni.cpp` | JNI bridge + SRT receive thread + state/access-unit callbacks. |
| `ts_demuxer.{h,cpp}` | Minimal MPEG-TS → H.264 access-unit depacketizer (the real work). |

## Current state: stub mode

`USE_LIBSRT` defaults **OFF** (set in `app/build.gradle.kts` →
`externalNativeBuild.cmake.arguments`). The module **compiles and the app runs**,
but `SrtVideoSource` will report `ERROR — libsrt not built`. That is expected: it
exercises the JNI + lifecycle + HUD path before the native lib exists.

To build the app you need, from the SDK Manager:
- **NDK (Side by side)** — pinned to `25.1.8937393` in `app/build.gradle.kts`
- **CMake** — `3.22.1`

## Going live: vendor + build libsrt

1. **Vendor the source** (submodule keeps it pinned & out of app history):
   ```sh
   git submodule add https://github.com/Haivision/srt \
       app/src/main/cpp/third_party/srt-src
   ```

2. **Build per ABI with mbedTLS** (lightest SSL backend for Android). Using
   libsrt's own helper script against the same NDK:
   ```sh
   cd app/src/main/cpp/third_party/srt-src
   ./scripts/build-android/build-android -n $ANDROID_NDK_HOME -t arm64-v8a
   ```
   Then stage the outputs where `CMakeLists.txt` expects them:
   ```
   third_party/srt/include/srt/*.h
   third_party/srt/libs/arm64-v8a/libsrt.a
   third_party/srt/libs/arm64-v8a/libmbedtls.a
   third_party/srt/libs/arm64-v8a/libmbedcrypto.a
   ```
   (For a closed LAN you can skip encryption and drop the mbedTLS libs; adjust
   `CMakeLists.txt` accordingly.)

3. **Flip the flag** in `app/build.gradle.kts`:
   ```kotlin
   arguments += "-DUSE_LIBSRT=ON"
   ```

4. **Fill in the two `TODO(M1)` blocks:**
   - `srt_jni.cpp::runReceive` — the `srt_startup → socket → bind/listen/accept
     (or connect) → recvmsg loop` per the inline outline.
   - `ts_demuxer.cpp::feed` — PAT/PMT → video PID → PES reassembly → PTS → emit.

5. **Swap the source** in `SrtIngestScreen.kt`: default `sourceFactory` from
   `StubVideoSource()` to `SrtVideoSource()`.

## Live validation checklist (real Mevo + tablet)

- Mevo SRT output → **Caller**, Host = tablet Wi-Fi IP, Port = `8890`, codec H.264.
- App URL stays `srt://0.0.0.0:8890?mode=listener` (tablet = listener).
- Watch the HUD: `CONNECTING → PLAYING`, sane FPS, and the latency readout.
- Tune `SRTO_LATENCY` (~80–120 ms on LAN) against the measured glass-to-glass lag.
