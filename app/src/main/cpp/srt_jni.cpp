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
#include <arpa/inet.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
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
    static std::atomic<bool> s_srtUp{false};
    bool expected = false;
    if (s_srtUp.compare_exchange_strong(expected, true)) srt_startup();

    // Parse srt://host:port[?...] -- we connect as the CALLER to the Mevo (listener).
    std::string u = s->url;
    auto sep = u.find("://"); if (sep != std::string::npos) u = u.substr(sep + 3);
    auto qm = u.find('?'); if (qm != std::string::npos) u = u.substr(0, qm);
    auto colon = u.find(':');
    std::string host = (colon == std::string::npos) ? u : u.substr(0, colon);
    int port = (colon == std::string::npos) ? 0 : atoi(u.substr(colon + 1).c_str());

    SRTSOCKET sock = SRT_INVALID_SOCK;
    if (host.empty() || port <= 0) {
        emitState(ERROR, "bad SRT URL");
    } else {
        sock = srt_create_socket();
        int live = SRTT_LIVE;
        srt_setsockflag(sock, SRTO_TRANSTYPE, &live, sizeof live);  // live defaults (TSBPD on)
        int latency = 120; srt_setsockflag(sock, SRTO_LATENCY, &latency, sizeof latency);
        bool yes = true;   srt_setsockflag(sock, SRTO_RCVSYN, &yes, sizeof yes);   // blocking recv
        int rcvto = 1000;  srt_setsockflag(sock, SRTO_RCVTIMEO, &rcvto, sizeof rcvto); // so stop() unblocks
        int conntimeo = 5000; srt_setsockflag(sock, SRTO_CONNTIMEO, &conntimeo, sizeof conntimeo);

        sockaddr_in sa{};
        sa.sin_family = AF_INET;
        sa.sin_port = htons((uint16_t) port);
        if (inet_pton(AF_INET, host.c_str(), &sa.sin_addr) != 1) {
            emitState(ERROR, "bad host address");
            srt_close(sock); sock = SRT_INVALID_SOCK;
        } else {
            LOGI("srt connect (caller) -> %s:%d", host.c_str(), port);
            if (srt_connect(sock, reinterpret_cast<sockaddr *>(&sa), sizeof sa) == SRT_ERROR) {
                int rej = srt_getrejectreason(sock);
                char msg[256];
                snprintf(msg, sizeof msg, "%s (reject %d: %s)",
                         srt_getlasterror_str(), rej, srt_rejectreason_str(rej));
                LOGE("srt_connect failed: %s", msg);
                emitState(ERROR, msg);
                srt_close(sock); sock = SRT_INVALID_SOCK;
            } else {
                emitState(BUFFERING, "connected, receiving");
                bool got = false;
                char buf[1500];
                while (s->running.load()) {
                    int n = srt_recvmsg(sock, buf, sizeof buf);
                    if (n == SRT_ERROR) {
                        int code = srt_getlasterror(nullptr);
                        if (code == SRT_ETIMEOUT || code == SRT_EASYNCRCV) continue;  // no data this tick
                        emitState(ERROR, srt_getlasterror_str());
                        break;
                    }
                    if (n > 0) {
                        if (!got) { got = true; emitState(PLAYING, "live"); }
                        demuxer.feed(reinterpret_cast<const uint8_t *>(buf), (size_t) n);
                    }
                }
                srt_close(sock); sock = SRT_INVALID_SOCK;
            }
        }
    }
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
