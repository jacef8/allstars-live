package com.libertyclerk.allstarslive.ingest

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * The boundary that keeps the M1 ingest spike honest about its one open decision.
 *
 * The Mevo feed can be pulled three ways (SRT / NDI|HX / RTMP), and the encoded
 * bytes can become on-screen pixels by very different routes:
 *   - FFmpeg AAR: receives `srt://`, demuxes the MPEG-TS, decodes, renders — all internal.
 *   - libsrt (NDK) + [MediaCodecVideoDecoder]: we demux TS and feed access units ourselves.
 *   - a maintained streaming-player lib: renders into the Surface for us.
 *
 * Every one of those can satisfy this contract: "given a Surface, render the live
 * feed into it and report what's happening." So the screen, lifecycle, and HUD are
 * built against this interface, and swapping the real transport in later touches
 * exactly one factory call — not the UI.
 */
interface VideoSource {

    /** Live stats for the on-screen HUD (fps / latency / state). */
    val stats: StateFlow<VideoStats>

    /**
     * Connect to [url] and begin rendering decoded frames into [surface].
     * Must be safe to call from the main thread; do the network/decode work
     * off-thread internally.
     */
    fun start(url: String, surface: Surface)

    /** Stop rendering and release all network/decoder resources. Idempotent. */
    fun stop()
}

enum class IngestState {
    IDLE,
    CONNECTING,
    BUFFERING,
    PLAYING,
    RECONNECTING,
    ERROR,
}

/**
 * Snapshot for the HUD. [latencyMs] is glass-to-glass *estimate* where the
 * source can compute it; otherwise it is the decode/render pipeline latency,
 * which is all the spike can honestly measure without a synced clock.
 */
data class VideoStats(
    val state: IngestState = IngestState.IDLE,
    val fps: Double = 0.0,
    val latencyMs: Long = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val framesRendered: Long = 0,
    val message: String = "",
)
