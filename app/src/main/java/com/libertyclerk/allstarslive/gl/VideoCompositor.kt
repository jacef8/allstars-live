package com.libertyclerk.allstarslive.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch

/**
 * The M2 compositor. Owns a GL thread + EGL context. The decoder renders into
 * [inputSurface] (a SurfaceTexture); each decoded frame is drawn — video first,
 * then the scorebug overlay — to the tablet preview, and (when recording) to the
 * encoder's input surface. The preview gets letterboxed to the video's aspect;
 * the encoder surface is video-sized so the recording is the clean program frame.
 */
class VideoCompositor {

    private val thread = HandlerThread("gl-compositor").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private lateinit var scene: GlScene
    private lateinit var surfaceTexture: SurfaceTexture
    private var oesTexId = 0
    private val texMatrix = FloatArray(16)

    private var displaySurface: EGLSurface? = null
    // Always-valid offscreen surface so the GL context survives with no preview
    // attached (encoder-only streaming while the operator scores on another tab).
    private var baseSurface: EGLSurface? = null

    // Encoder target (Step 3) — null until recording.
    private var encoderSurface: EGLSurface? = null
    private var encoderW = 0
    private var encoderH = 0
    // Wall-clock base captured at the first encoder frame, so the encoder's video PTS is
    // ZERO-BASED on the same real-time clock the audio track uses — otherwise YouTube can't
    // sync them (stuck "starting", recorded-but-no-video). Wall-clock (not the camera
    // clock) also lets keep-alive frames advance smoothly when the camera has stalled.
    // -1 = not yet set; reset whenever a new encoder attaches.
    private var encoderBaseNs = -1L
    private var onEncoderFrame: ((Long) -> Unit)? = null

    // Keep-alive: if the camera stalls (e.g. Mevo dropped) WHILE streaming, keep pushing
    // the last frame to the encoder so the RTMP stream — and thus the YouTube broadcast —
    // stays alive instead of YouTube auto-stopping it. Resumes real frames when the camera
    // returns. lastCamNs = wall time (ms) of the last real camera frame.
    @Volatile private var lastCamNs = 0L
    // Drive the ENCODER at a steady ~30fps, DECOUPLED from camera-frame arrival, so the YouTube
    // stream has a constant frame rate (smooth) even when the camera delivers frames unevenly — which
    // was the cause of the choppy video on good networks. Each tick re-renders the latest camera frame
    // + overlay (so a camera stall also just freezes the last frame, keeping RTMP alive). Idles slowly
    // when not streaming so it costs nothing off-air.
    private val encoderTick = object : Runnable {
        override fun run() {
            val attached = encoderSurface != null
            if (attached) runCatching { renderEncoder() }
            handler.postDelayed(this, if (attached) FRAME_MS else IDLE_MS)
        }
    }

    // Overlay (Step 2).
    private var overlayTexId = 0
    @Volatile private var hasOverlay = false

    @Volatile private var videoAspect = 16f / 9f

    /** The surface the decoder renders into. Valid after [start] returns. */
    lateinit var inputSurface: Surface
        private set

    /**
     * Initialise GL. [display] is optional: pass it for an immediate preview, or omit
     * to start headless (the pipeline runs and can stream while the preview is later
     * attached via [attachPreview]). Blocks until ready.
     */
    fun start(display: Surface? = null) {
        val latch = CountDownLatch(1)
        handler.post {
            egl = EglCore()
            baseSurface = egl.createOffscreenSurface(1, 1)
            displaySurface = display?.let { egl.createWindowSurface(it) }
            egl.makeCurrent(displaySurface ?: baseSurface!!)
            scene = GlScene()
            oesTexId = scene.createOesTexture()
            overlayTexId = scene.createTexture()
            surfaceTexture = SurfaceTexture(oesTexId).apply {
                setOnFrameAvailableListener({ handler.post(::drawFrame) }, handler)
            }
            inputSurface = Surface(surfaceTexture)
            latch.countDown()
        }
        latch.await()
        handler.postDelayed(encoderTick, FRAME_MS)   // steady ~30fps encoder cadence (smooth stream)
        Log.i(TAG, "compositor started")
    }

    /** Attach (or replace) the on-screen preview surface. Safe to call any time after [start]. */
    fun attachPreview(display: Surface) {
        handler.post {
            displaySurface?.let { egl.releaseSurface(it) }
            displaySurface = egl.createWindowSurface(display)
        }
    }

    /** Drop the on-screen preview (tab switched away); the encoder path keeps running. */
    fun detachPreview() {
        handler.post {
            displaySurface?.let { egl.releaseSurface(it) }
            displaySurface = null
            baseSurface?.let { egl.makeCurrent(it) }   // keep the context current on the base
        }
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) videoAspect = width.toFloat() / height
    }

    /**
     * Size the buffers the [inputSurface] produces. Required when a *Canvas* producer
     * (the test-pattern source) draws into it; a MediaCodec decoder sets its own size.
     */
    fun setInputSize(width: Int, height: Int) {
        handler.post {
            if (::surfaceTexture.isInitialized && width > 0 && height > 0) {
                surfaceTexture.setDefaultBufferSize(width, height)
            }
        }
    }

    /**
     * Detach the encoder and run [finish] — both on the GL thread, so every
     * MediaCodec/muxer call (draw, drain, stop) stays on the one owning thread.
     */
    fun detachEncoder(finish: () -> Unit) {
        handler.post {
            encoderSurface?.let { egl.releaseSurface(it) }
            encoderSurface = null
            onEncoderFrame = null
            runCatching { finish() }
        }
    }

    /** Replace the scorebug bitmap (premultiplied alpha). Pass null to hide it. */
    fun setOverlay(bitmap: Bitmap?) {
        handler.post {
            if (bitmap == null) {
                hasOverlay = false
            } else {
                scene.updateOverlay(overlayTexId, bitmap)
                hasOverlay = true
            }
        }
    }

    /** Attach an encoder input [surface] (video-sized) to also receive the composite. */
    fun setEncoderSurface(surface: Surface?, width: Int, height: Int, onFrame: ((Long) -> Unit)?) {
        handler.post {
            encoderSurface?.let { egl.releaseSurface(it) }
            encoderBaseNs = -1L          // re-zero the video clock for the new stream
            if (surface == null) {
                encoderSurface = null
                onEncoderFrame = null
            } else {
                encoderSurface = egl.createWindowSurface(surface)
                encoderW = width
                encoderH = height
                onEncoderFrame = onFrame
            }
        }
    }

    private fun drawFrame() {
        val base = baseSurface ?: return
        // Keep the context current on an always-valid surface so updateTexImage works
        // even with no preview and no encoder attached.
        egl.makeCurrent(displaySurface ?: base)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        lastCamNs = System.currentTimeMillis()   // a real frame arrived (keep-alive backs off)

        // Preview (if attached): letterbox the video to the screen, overlay on top.
        displaySurface?.let { display ->
            egl.makeCurrent(display)
            val dw = egl.getWidth(display)
            val dh = egl.getHeight(display)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            letterboxViewport(dw, dh)
            scene.drawVideo(oesTexId, texMatrix)
            if (hasOverlay) {
                GLES20.glViewport(0, 0, dw, dh)
                scene.drawOverlay(overlayTexId)
            }
            egl.swapBuffers(display)
        }
        // NOTE: the encoder is NOT pushed here — encoderTick drives it at a steady ~30fps so the
        // outgoing stream stays smooth even when these camera frames arrive unevenly.
    }

    /** Draw the clean program frame (last camera frame + overlay) to the encoder. Called
     *  per real frame from [drawFrame] AND by the keep-alive [heartbeat] during a camera
     *  stall — both re-use whatever [oesTexId]/[texMatrix] currently hold. */
    private fun renderEncoder() {
        val enc = encoderSurface ?: return
        egl.makeCurrent(enc)
        GLES20.glViewport(0, 0, encoderW, encoderH)
        scene.drawVideo(oesTexId, texMatrix)
        if (hasOverlay) scene.drawOverlay(overlayTexId)
        // Wall-clock, zero-based PTS so video + audio share a timeline YouTube can sync,
        // and so keep-alive frames advance smoothly with no camera timestamp.
        val now = System.nanoTime()
        if (encoderBaseNs < 0) encoderBaseNs = now
        val encPts = now - encoderBaseNs
        egl.setPresentationTime(enc, encPts)
        egl.swapBuffers(enc)
        onEncoderFrame?.invoke(encPts)
    }

    private fun letterboxViewport(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val dispAspect = w.toFloat() / h
        if (dispAspect > videoAspect) {
            val vpW = (h * videoAspect).toInt()
            GLES20.glViewport((w - vpW) / 2, 0, vpW, h)
        } else {
            val vpH = (w / videoAspect).toInt()
            GLES20.glViewport(0, (h - vpH) / 2, w, vpH)
        }
    }

    fun release() {
        val latch = CountDownLatch(1)
        handler.removeCallbacks(heartbeat)
        handler.post {
            runCatching { surfaceTexture.release() }
            runCatching { inputSurface.release() }
            encoderSurface?.let { egl.releaseSurface(it) }
            displaySurface?.let { egl.releaseSurface(it) }
            baseSurface?.let { egl.releaseSurface(it) }
            runCatching { egl.release() }
            latch.countDown()
        }
        latch.await()
        thread.quitSafely()
        Log.i(TAG, "compositor released")
    }

    companion object {
        private const val TAG = "VideoCompositor"
        private const val FRAME_MS = 33L   // ~30fps steady encoder cadence (smooth YouTube stream)
        private const val IDLE_MS = 200L   // slow tick when no encoder is attached (off-air)
    }
}
