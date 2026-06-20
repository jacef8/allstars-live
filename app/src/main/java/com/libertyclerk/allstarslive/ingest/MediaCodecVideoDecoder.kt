package com.libertyclerk.allstarslive.ingest

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * The real M1 "decode-to-surface" harness for the libsrt route.
 *
 * It is transport-free on purpose: it consumes already-demuxed H.264/H.265
 * access units (Annex-B, with in-band SPS/PPS or supplied as csd) and renders
 * them to a [Surface], measuring rolling FPS and decode→render latency for the
 * HUD. Whatever pulls SRT and demuxes the MPEG-TS just calls [submitAccessUnit].
 *
 * Uses the asynchronous MediaCodec callback API so there is no polling loop and
 * back-pressure is handled by the codec's own buffer availability.
 */
class MediaCodecVideoDecoder(
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    private val surface: Surface,
    private val onStats: (fps: Double, latencyMs: Long, frames: Long, w: Int, h: Int) -> Unit,
) {
    private var codec: MediaCodec? = null
    private val availableInputs = ArrayDeque<Int>()
    private val lock = Any()

    // pts(us) -> submit time(ns), to estimate decode→render latency for the HUD.
    private val submitNanos = ConcurrentHashMap<Long, Long>()

    @Volatile private var started = false
    private var framesRendered = 0L
    private var fpsWindowStartNs = 0L
    private var fpsWindowFrames = 0
    private var lastFps = 0.0
    private var lastLatencyMs = 0L
    private var widthPx = 0
    private var heightPx = 0

    /** Start the decoder. csd0/csd1 are optional explicit SPS/PPS (NDI/RTMP often supply them out of band). */
    fun start(width: Int = 1920, height: Int = 1080, csd0: ByteArray? = null, csd1: ByteArray? = null) {
        if (started) return
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            csd0?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        }
        val c = MediaCodec.createDecoderByType(mimeType)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                synchronized(lock) { availableInputs.addLast(index) }
            }

            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // render=true pushes the frame to the Surface.
                val render = info.size > 0
                mc.releaseOutputBuffer(index, render)
                if (render) onFrameRendered(info.presentationTimeUs)
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                widthPx = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(widthPx)
                heightPx = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(heightPx)
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "decoder error (recoverable=${e.isRecoverable}, transient=${e.isTransient})", e)
            }
        })
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        widthPx = width
        heightPx = height
        fpsWindowStartNs = SystemClock.elapsedRealtimeNanos()
        started = true
        Log.i(TAG, "decoder started: $mimeType ${width}x$height")
    }

    /**
     * Feed one access unit. [presentationTimeUs] should be monotonic; pass the
     * source PTS if you have it, else a synthetic frame clock.
     */
    fun submitAccessUnit(data: ByteArray, presentationTimeUs: Long, isKeyframe: Boolean) {
        val c = codec ?: return
        val index = synchronized(lock) { if (availableInputs.isEmpty()) -1 else availableInputs.removeFirst() }
        if (index < 0) return // no input buffer free -> drop; the codec is back-pressuring
        val buf = c.getInputBuffer(index) ?: return
        buf.clear()
        buf.put(data)
        val flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        submitNanos[presentationTimeUs] = SystemClock.elapsedRealtimeNanos()
        c.queueInputBuffer(index, 0, data.size, presentationTimeUs, flags)
    }

    private fun onFrameRendered(ptsUs: Long) {
        framesRendered++
        fpsWindowFrames++

        submitNanos.remove(ptsUs)?.let { submittedNs ->
            lastLatencyMs = (SystemClock.elapsedRealtimeNanos() - submittedNs) / 1_000_000
        }
        // Keep the latency map from growing if PTSs are noisy.
        if (submitNanos.size > 120) submitNanos.clear()

        val now = SystemClock.elapsedRealtimeNanos()
        val elapsed = now - fpsWindowStartNs
        if (elapsed >= 1_000_000_000L) {
            lastFps = fpsWindowFrames * 1e9 / elapsed
            fpsWindowFrames = 0
            fpsWindowStartNs = now
            onStats(lastFps, lastLatencyMs, framesRendered, widthPx, heightPx)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        synchronized(lock) { availableInputs.clear() }
        submitNanos.clear()
        Log.i(TAG, "decoder stopped after $framesRendered frames")
    }

    companion object {
        private const val TAG = "MediaCodecDecoder"
    }
}
