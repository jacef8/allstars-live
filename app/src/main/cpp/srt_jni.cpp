// JNI bridge for the libsrt ingest route.
//
// Owns the SRT receive thread, feeds bytes to the TsDemuxer, and calls back into
// SrtVideoSource.kt with demuxed H.264 access units + transport state. The real
// libsrt socket calls live behind USE_LIBSRT; with it OFF this compiles and runs
// in a stub mode that just reports ERROR so the HUD stays honest.
//
// Assumes a single active session at a time (one Mevo, one tablet) -- enough for
// the M1 spike. Generalize the globals to a per-handle map if that changes.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <string>
#include <thread>

#include "ts_demuxer.h"

#if USE_LIBSRT
#include <srt/srt.h>
#endif

#define LOG_TAG "SrtJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
jobject g_listener = nullptr;        // global ref to the SrtVideoSource instance
jmethodID g_onAccessUnit = nullptr;  // ([B, long ptsUs, boolean keyframe)
jmethodID g_onState = nullptr;       // (int state, String message)

// Must stay in lockstep with IngestState's declaration order in VideoSource.kt.
enum NativeState { IDLE = 0, CONNECTING = 1, BUFFERING = 2, PLAYING = 3, RECONNECTING = 4, ERROR = 5 };

struct Session {
    std::atomic<bool> running{false};
    std::thread worker;
    std::string url;
};

JNIEnv *attachEnv(bool *didAttach) {
    JNIEnv *env = nullptr;
    *didAttach = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) *didAttach = true;
    }
    return env;
}

void emitState(NativeState state, const char *msg) {
    bool didAttach;
    JNIEnv *env = attachEnv(&didAttach);
    if (env && g_listener) {
        jstring jmsg = env->NewStringUTF(msg ? msg : "");
        env->CallVoidMethod(g_listener, g_onState, static_cast<jint>(state), jmsg);
        env->DeleteLocalRef(jmsg);
    }
    if (didAttach) g_vm->DetachCurrentThread();
}

// TsDemuxer callback: marshal one access unit up to Kotlin.
void onAccessUnit(const uint8_t *data, size_t size, int64_t ptsUs, bool keyframe, void *) {
    bool didAttach;
    JNIEnv *env = attachEnv(&didAttach);
    if (env && g_listener) {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size),
                                reinterpret_cast<const jbyte *>(data));
        env->CallVoidMethod(g_listener, g_onAccessUnit, arr, static_cast<jlong>(ptsUs),
                            static_cast<jboolean>(keyframe));
        env->DeleteLocalRef(arr);
    }
    if (didAttach) g_vm->DetachCurrentThread();
}

void runReceive(Session *s) {
    TsDemuxer demuxer(onAccessUnit, nullptr);
    emitState(CONNECTING, "opening SRT socket");

#if USE_LIBSRT
    // ---- TODO(M1): real libsrt receive loop (see cpp/README.md) ----
    //  1) srt_startup() once (here or in JNI_OnLoad).
    //  2) Parse s->url -> mode (listener/caller), host, port, latency, passphrase.
    //  3) listener: srt_create_socket -> srt_bind -> srt_listen -> srt_accept.
    //     caller:   srt_create_socket -> srt_connect.
    //  4) Options: SRTO_RCVSYN=true, SRTO_TSBPDMODE=1, SRTO_LATENCY (~80-120ms LAN).
    //  5) Loop: int n = srt_recvmsg(sock, buf, sizeof buf); demuxer.feed(buf, n);
    //     emit PLAYING on first good recv, RECONNECTING on transient error.
    //  6) On exit: srt_close(sock); srt_cleanup() at process end.
    uint8_t buf[1500];
    (void) buf;  // remove once the loop above is filled in
    emitState(ERROR, "USE_LIBSRT on but receive loop not implemented yet");
#else
    emitState(ERROR, "libsrt not built (USE_LIBSRT=OFF) -- see cpp/README.md");
#endif

    // Idle until stop() so state/lifecycle can be tested without a live feed.
    while (s->running.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    emitState(IDLE, "");
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_libertyclerk_allstarslive_ingest_SrtVideoSource_nativeStart(
        JNIEnv *env, jobject thiz, jstring jurl) {
    if (!g_listener) {
        g_listener = env->NewGlobalRef(thiz);
        jclass cls = env->GetObjectClass(thiz);
        g_onAccessUnit = env->GetMethodID(cls, "onAccessUnit", "([BJZ)V");
        g_onState = env->GetMethodID(cls, "onNativeState", "(ILjava/lang/String;)V");
    }

    auto *s = new Session();
    const char *c = env->GetStringUTFChars(jurl, nullptr);
    s->url = c ? c : "";
    env->ReleaseStringUTFChars(jurl, c);

    s->running.store(true);
    s->worker = std::thread(runReceive, s);
    LOGI("nativeStart: %s", s->url.c_str());
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_libertyclerk_allstarslive_ingest_SrtVideoSource_nativeStop(
        JNIEnv *env, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    s->running.store(false);
    if (s->worker.joinable()) s->worker.join();
    delete s;

    if (g_listener) {
        env->DeleteGlobalRef(g_listener);
        g_listener = nullptr;
    }
    LOGI("nativeStop");
}
