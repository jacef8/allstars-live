package com.libertyclerk.allstarslive.ingest

import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Real SRT [VideoSource] for the libsrt + NDK route — the recommended M1 path
 * (see the srt-ingest-decision memo).
 *
 * Pipeline:
 *   native libsrt recv + MPEG-TS demux (app/src/main/cpp)
 *     -> [onAccessUnit] (this class, called from the native recv thread)
 *       -> [MediaCodecVideoDecoder] (HW decode straight to the [Surface], emits HUD stats)
 *
 * This is the single swap the architecture was built for: change the default
 * `sourceFactory` in SrtIngestScreen from `StubVideoSource()` to `SrtVideoSource()`
 * once the native build is green.
 *
 * SKELETON: the native lib compiles in stub mode (USE_LIBSRT=OFF) until libsrt is
 * vendored — see app/src/main/cpp/README.md. In stub mode the HUD will show ERROR
 * ("libsrt not built"); that is expected and proves the JNI/lifecycle plumbing.
 */
class SrtVideoSource : VideoSource {

    private val _stats = MutableStateFlow(VideoStats())
    override val stats: StateFlow<VideoStats> = _stats

    private var decoder: MediaCodecVideoDecoder? = null
    private var nativeHandle: Long = 0L
    @Volatile private var firstFrameSeen = false

    override fun start(url: String, surface: Surface) {
        if (nativeHandle != 0L) return // already running; stop() first
        firstFrameSeen = false

        // Mevo defaults to H.264. Width/height are placeholders until the demuxer
        // reads SPS or onOutputFormatChanged corrects them.
        decoder = MediaCodecVideoDecoder(
            mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
            surface = surface,
            onStats = { fps, latencyMs, frames, w, h ->
                _stats.value = _stats.value.copy(
                    state = IngestState.PLAYING,
                    fps = fps,
                    latencyMs = latencyMs,
                    framesRendered = frames,
                    widthPx = w,
                    heightPx = h,
                )
            },
        ).also { it.start() }

        _stats.value = VideoStats(state = IngestState.CONNECTING, message = "starting SRT")
        nativeHandle = nativeStart(url)
        if (nativeHandle == 0L) {
            _stats.value = VideoStats(state = IngestState.ERROR, message = "native start failed")
        }
    }

    override fun stop() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) nativeStop(handle)
        decoder?.stop()
        decoder = null
        _stats.value = VideoStats(state = IngestState.IDLE)
    }

    /** Called from the native receive thread with one demuxed H.264 access unit. */
    @Suppress("unused") // invoked via JNI (see srt_jni.cpp)
    private fun onAccessUnit(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
        if (!firstFrameSeen) {
            firstFrameSeen = true
            _stats.value = _stats.value.copy(state = IngestState.PLAYING, message = "")
        }
        decoder?.submitAccessUnit(data, ptsUs, keyframe)
    }

    /** Called from native to mirror transport state into the HUD. */
    @Suppress("unused") // invoked via JNI (see srt_jni.cpp)
    private fun onNativeState(state: Int, message: String) {
        // `state` is an IngestState ordinal; keep srt_jni.cpp's NativeState in sync.
        val mapped = IngestState.entries.getOrElse(state) { IngestState.ERROR }
        _stats.value = _stats.value.copy(state = mapped, message = message)
    }

    private external fun nativeStart(url: String): Long
    private external fun nativeStop(handle: Long)

    companion object {
        init { System.loadLibrary("srtjni") }
    }
}
