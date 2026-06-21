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

    // Encoder target (Step 3) — null until recording.
    private var encoderSurface: EGLSurface? = null
    private var encoderW = 0
    private var encoderH = 0
    private var onEncoderFrame: ((Long) -> Unit)? = null

    // Overlay (Step 2).
    private var overlayTexId = 0
    @Volatile private var hasOverlay = false

    @Volatile private var videoAspect = 16f / 9f

    /** The surface the decoder renders into. Valid after [start] returns. */
    lateinit var inputSurface: Surface
        private set

    /** Initialise GL against the preview [display] surface. Blocks until ready. */
    fun start(display: Surface) {
        val latch = CountDownLatch(1)
        handler.post {
            egl = EglCore()
            displaySurface = egl.createWindowSurface(display)
            egl.makeCurrent(displaySurface!!)
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
        Log.i(TAG, "compositor started")
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) videoAspect = width.toFloat() / height
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
        val display = displaySurface ?: return
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        val ptsNs = surfaceTexture.timestamp

        // Preview: letterbox the video to the screen, overlay on top.
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

        // Recording: draw the clean program frame at full encoder resolution.
        encoderSurface?.let { enc ->
            egl.makeCurrent(enc)
            GLES20.glViewport(0, 0, encoderW, encoderH)
            scene.drawVideo(oesTexId, texMatrix)
            if (hasOverlay) scene.drawOverlay(overlayTexId)
            egl.setPresentationTime(enc, ptsNs)
            egl.swapBuffers(enc)
            onEncoderFrame?.invoke(ptsNs)
        }
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
        handler.post {
            runCatching { surfaceTexture.release() }
            runCatching { inputSurface.release() }
            encoderSurface?.let { egl.releaseSurface(it) }
            displaySurface?.let { egl.releaseSurface(it) }
            runCatching { egl.release() }
            latch.countDown()
        }
        latch.await()
        thread.quitSafely()
        Log.i(TAG, "compositor released")
    }

    companion object {
        private const val TAG = "VideoCompositor"
    }
}
