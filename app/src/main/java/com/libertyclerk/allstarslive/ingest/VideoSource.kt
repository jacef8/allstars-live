package com.libertyclerk.allstarslive.ingest

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * "Given a Surface, render a live feed into it and report what's happening." Implemented by
 * [RtmpVideoSource] (the real camera path, backed by [RtmpHub]) and [StubVideoSource] (the test
 * pattern). [IngestState] / [VideoStats] are what the HUD and the web scorer's camera status read.
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
