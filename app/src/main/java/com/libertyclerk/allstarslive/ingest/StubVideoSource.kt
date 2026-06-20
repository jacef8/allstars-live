package com.libertyclerk.allstarslive.ingest

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * A standing-in [VideoSource] so the spike screen, lifecycle, and HUD are real
 * and testable on the tablet *before* the SRT ingest library is chosen.
 *
 * It draws a moving SMPTE-ish test pattern + frame clock directly onto the
 * Surface at ~30fps and reports honest render stats. It pulls no network — when
 * the real transport lands, [SrtIngestScreen] swaps this for it at one call site
 * and nothing else changes.
 */
class StubVideoSource : VideoSource {

    private val _stats = MutableStateFlow(VideoStats())
    override val stats: StateFlow<VideoStats> = _stats

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun start(url: String, surface: Surface) {
        if (running) return
        running = true
        _stats.value = VideoStats(state = IngestState.CONNECTING, message = "PLACEHOLDER source (no SRT yet)")

        worker = thread(name = "stub-video") {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var frames = 0L
            var fpsFrames = 0
            var fpsStart = SystemClock.elapsedRealtimeNanos()
            var fps = 0.0
            val startMs = SystemClock.elapsedRealtime()

            while (running) {
                val frameStartNs = SystemClock.elapsedRealtimeNanos()
                val canvas = runCatching { surface.lockCanvas(null) }.getOrNull()
                if (canvas == null) { SystemClock.sleep(16); continue }
                try {
                    val w = canvas.width
                    val h = canvas.height
                    canvas.drawColor(Color.rgb(8, 8, 12))

                    // Color bars.
                    val bars = intArrayOf(
                        Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN,
                        Color.MAGENTA, Color.RED, Color.BLUE,
                    )
                    val barW = w / bars.size
                    for (i in bars.indices) {
                        paint.color = bars[i]
                        canvas.drawRect(Rect(i * barW, 0, (i + 1) * barW, h / 2), paint)
                    }

                    // A sweeping bar so motion/latency is visible to the eye.
                    val t = (SystemClock.elapsedRealtime() - startMs) / 1000.0
                    val x = ((sin(t) * 0.5 + 0.5) * (w - 80)).toFloat()
                    paint.color = Color.rgb(79, 156, 255)
                    canvas.drawRect(x, h / 2f, x + 80f, h.toFloat(), paint)

                    // Frame clock.
                    paint.color = Color.WHITE
                    paint.textSize = h / 14f
                    canvas.drawText("PLACEHOLDER  frame $frames", 24f, h * 0.62f, paint)
                    paint.textSize = h / 22f
                    canvas.drawText("Swap StubVideoSource → real SRT transport", 24f, h * 0.72f, paint)
                } finally {
                    runCatching { surface.unlockCanvasAndPost(canvas) }
                }

                frames++
                fpsFrames++
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - fpsStart >= 1_000_000_000L) {
                    fps = fpsFrames * 1e9 / (now - fpsStart)
                    fpsFrames = 0
                    fpsStart = now
                }
                _stats.value = VideoStats(
                    state = IngestState.PLAYING,
                    fps = fps,
                    latencyMs = 0,
                    framesRendered = frames,
                    message = "PLACEHOLDER — not the Mevo feed",
                )

                // Pace to ~30fps.
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - frameStartNs) / 1_000_000
                val sleep = 33 - elapsedMs
                if (sleep > 0) SystemClock.sleep(sleep)
            }
            _stats.value = VideoStats(state = IngestState.IDLE)
        }
    }

    override fun stop() {
        running = false
        worker?.join(500)
        worker = null
        _stats.value = VideoStats(state = IngestState.IDLE)
    }
}
