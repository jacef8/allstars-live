package com.libertyclerk.allstarslive.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * "All-in-one" mode: capture THIS device's built-in camera straight into the compositor's
 * input surface. The same downstream the RTMP path uses — scorebug overlay → tablet preview
 * → YouTube/record — then works unchanged, so one device can score AND stream.
 *
 * Camera2 renders into the [VideoCompositor]'s SurfaceTexture-backed input surface exactly like
 * the MediaCodec decoder does in the external-camera path. We report the chosen resolution
 * (so the compositor can size its buffer + aspect) and fire [onFrame] per captured frame so the
 * hub can flip to PLAYING and compute fps.
 */
class DeviceCamera(private val context: Context) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var closed = false

    /**
     * Open the [facingBack] camera (falls back to any camera) and feed frames into [target].
     * [onSize] reports the chosen resolution BEFORE frames flow (the compositor sizes its
     * SurfaceTexture to it); [onFrame] fires per captured frame; [onError] on any failure.
     * Safe to call from the main thread — all camera work runs on a private thread.
     */
    fun start(
        target: Surface,
        facingBack: Boolean,
        onSize: (Int, Int) -> Unit,
        onFrame: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Camera permission needed"); return
        }
        closed = false
        val t = HandlerThread("device-camera").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val camId = pickCamera(mgr, facingBack) ?: run { onError("No camera on this device"); return }
            val chars = mgr.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = pickSize(map?.getOutputSizes(SurfaceTexture::class.java))
            onSize(size.width, size.height)   // compositor sizes its input buffer to this first

            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    if (closed) { runCatching { cam.close() }; return }
                    try {
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(target)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            // Aim for a steady 30fps so the encoded stream is smooth.
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(24, 30))
                        }
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (closed) { runCatching { s.close() }; return }
                                session = s
                                runCatching {
                                    s.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(se: CameraCaptureSession, rq: CaptureRequest, r: TotalCaptureResult) {
                                            onFrame()
                                        }
                                    }, h)
                                }.onFailure { onError("Couldn't start the camera preview") }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                onError("Camera session failed")
                            }
                        }, h)
                    } catch (e: Exception) {
                        Log.e(TAG, "session setup failed", e); onError(e.message ?: "Camera failed")
                    }
                }

                override fun onDisconnected(cam: CameraDevice) { runCatching { cam.close() }; device = null }

                override fun onError(cam: CameraDevice, err: Int) {
                    runCatching { cam.close() }; device = null
                    onError("Camera error ($err)")
                }
            }, h)
        } catch (e: Exception) {
            Log.e(TAG, "device camera start failed", e); onError(e.message ?: "Camera failed")
        }
    }

    /** Release the camera + session + thread. Idempotent. */
    fun stop() {
        closed = true
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null; device = null
        runCatching { thread?.quitSafely() }
        thread = null; handler = null
    }

    /** Prefer the requested lens; fall back to the first camera that exists. */
    private fun pickCamera(mgr: CameraManager, back: Boolean): String? {
        val want = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        var fallback: String? = null
        for (id in mgr.cameraIdList) {
            val f = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (f == want) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    /** 720p if available; otherwise the largest 16:9-ish size up to 1080p. */
    private fun pickSize(sizes: Array<Size>?): Size {
        if (sizes == null || sizes.isEmpty()) return Size(1280, 720)
        sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return it }
        return sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
    }

    companion object { private const val TAG = "DeviceCamera" }
}
