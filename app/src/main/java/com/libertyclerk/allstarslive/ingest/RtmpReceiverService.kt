package com.libertyclerk.allstarslive.ingest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.libertyclerk.allstarslive.gl.VideoCompositor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The always-on camera pipeline: RTMP receiver → decoder → compositor, all living
 * here (not in any screen) so it keeps running while the operator is on the Game tab
 * scoring or away in the Mevo app. The on-screen preview is an *optional* attachment
 * ([attachPreview]); the YouTube/record encoder attaches to [videoCompositor].
 *
 *   [RtmpReceiver] (FGS, :PORT)  →  H.264 Annex-B  →  [MediaCodecVideoDecoder]
 *     →  [VideoCompositor]  →  preview (when a tab shows it) + encoder (when live)
 */
object RtmpHub {
    private val _stats = MutableStateFlow(VideoStats())
    val stats: StateFlow<VideoStats> = _stats

    @Volatile var port = 1935
    @Volatile var publishHint = "Waiting for camera…"; private set

    private var receiver: RtmpReceiver? = null
    private var decoder: MediaCodecVideoDecoder? = null
    private var compositor: VideoCompositor? = null
    private var deviceCamera: DeviceCamera? = null   // all-in-one (this device's camera) mode

    /** The compositor, once the pipeline is up — for encoder (stream/record) + overlay. */
    val videoCompositor: VideoCompositor? get() = compositor

    /** How video gets in: "external" = a camera pushes RTMP to us; "device" = our own camera. */
    @Volatile var captureMode = "external"   // == MODE_EXTERNAL (const declared below)
    @Volatile var lensBack = true
    private var camFrames = 0L
    private var camFpsAnchorMs = 0L

    private var sps: ByteArray? = null   // Annex-B (from the AVC config record)
    private var pps: ByteArray? = null
    @Volatile private var firstFrameSeen = false
    @Volatile private var lastFrameMs = 0L
    private var watchdog: Thread? = null
    private var pendingPreview: Surface? = null

    val isRunning: Boolean get() = receiver != null || deviceCamera != null

    /** True once the camera has actually delivered a decodable frame (not just connected). */
    val hasVideo: Boolean get() = firstFrameSeen

    @Synchronized
    fun start(port: Int) {
        this.port = port
        if (receiver != null) return
        firstFrameSeen = false; sps = null; pps = null

        val comp = VideoCompositor().also { it.start() }   // headless; preview attaches later
        compositor = comp
        pendingPreview?.let { comp.attachPreview(it) }

        decoder = MediaCodecVideoDecoder(
            mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
            surface = comp.inputSurface,
            onStats = { fps, latencyMs, frames, w, h ->
                comp.setVideoSize(w, h)
                _stats.value = _stats.value.copy(
                    state = IngestState.PLAYING,
                    fps = fps, latencyMs = latencyMs, framesRendered = frames,
                    widthPx = w, heightPx = h,
                )
            },
        ).also { it.start() }

        val ip = localWifiIp()
        Log.i(TAG, "local Wi-Fi IP = $ip")
        publishHint = if (ip != null) "rtmp://$ip:$port/live" else "Waiting for camera…"
        _stats.value = VideoStats(state = IngestState.CONNECTING, message = publishHint)

        receiver = RtmpReceiver(
            port = port,
            onConfig = { s, p -> sps = s; pps = p },
            onVideo = { au, ptsMs, kf -> onVideo(au, ptsMs, kf) },
            onStatus = { m ->
                if (!firstFrameSeen) {
                    val show = if (m.startsWith("Waiting")) publishHint else m
                    _stats.value = _stats.value.copy(message = show)
                }
            },
        ).also { it.start() }

        // Watchdog: if frames stop after we were playing (camera dropped), show a clear
        // "reconnecting" status. The receiver keeps accepting reconnects, so it recovers
        // on its own once the camera comes back (onVideo flips us back to PLAYING).
        lastFrameMs = System.currentTimeMillis()
        watchdog = Thread {
            while (receiver != null) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                if (firstFrameSeen && _stats.value.state == IngestState.PLAYING &&
                    System.currentTimeMillis() - lastFrameMs > 3000
                ) {
                    _stats.value = _stats.value.copy(state = IngestState.RECONNECTING, message = "Camera reconnecting…")
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * All-in-one mode: bring the compositor up and feed it from THIS device's camera instead of
     * an RTMP receiver. Everything downstream (overlay, preview, Go Live, record) is identical —
     * it just draws frames from the built-in camera. Runs in-process (no foreground service):
     * the operator stays in the app while scoring, so the camera stays foreground-active.
     */
    @Synchronized
    fun startDeviceCamera(context: Context) {
        captureMode = MODE_DEVICE
        if (compositor != null) return
        firstFrameSeen = false; camFrames = 0; camFpsAnchorMs = 0

        val comp = VideoCompositor().also { it.start() }   // headless; preview attaches later
        compositor = comp
        pendingPreview?.let { comp.attachPreview(it) }
        _stats.value = VideoStats(state = IngestState.CONNECTING, message = "Starting camera…")

        deviceCamera = DeviceCamera(context.applicationContext).also { cam ->
            cam.start(
                target = comp.inputSurface,
                facingBack = lensBack,
                onSize = { w, h -> comp.setVideoSize(w, h); comp.setInputSize(w, h) },
                onFrame = { onCameraFrame() },
                onError = { msg -> _stats.value = _stats.value.copy(state = IngestState.ERROR, message = msg) },
            )
        }
    }

    /** Per-frame callback from the device camera: flip to PLAYING, keep a light fps estimate. */
    private fun onCameraFrame() {
        lastFrameMs = System.currentTimeMillis()
        if (!firstFrameSeen) {
            firstFrameSeen = true
            camFpsAnchorMs = lastFrameMs; camFrames = 0
            _stats.value = _stats.value.copy(state = IngestState.PLAYING, message = "")
        }
        camFrames++
        val dt = lastFrameMs - camFpsAnchorMs
        if (camFrames >= 30 && dt > 0) {
            _stats.value = _stats.value.copy(fps = camFrames * 1000.0 / dt, framesRendered = _stats.value.framesRendered + camFrames)
            camFrames = 0; camFpsAnchorMs = lastFrameMs
        }
    }

    /** Bring the pipeline up in whichever mode is selected — used by Go Live so a broadcast
     *  works even if the operator never opened the Video screen first. */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (compositor != null) return
        if (captureMode == MODE_DEVICE) startDeviceCamera(context)
        else { RtmpReceiverService.start(context, port); start(port) }
    }

    private fun onVideo(au: ByteArray, ptsMs: Long, keyframe: Boolean) {
        lastFrameMs = System.currentTimeMillis()
        if (!firstFrameSeen) {
            firstFrameSeen = true
            _stats.value = _stats.value.copy(state = IngestState.PLAYING, message = "")
        } else if (_stats.value.state == IngestState.RECONNECTING) {
            _stats.value = _stats.value.copy(state = IngestState.PLAYING, message = "")
        }
        // The decoder takes in-band Annex-B: prepend SPS/PPS to each keyframe so it can
        // configure/recover (and so a sink attaching mid-stream starts at the next key).
        val data = if (keyframe) prepend(au) else au
        runCatching { decoder?.submitAccessUnit(data, ptsMs * 1000, keyframe) }
    }

    private fun prepend(au: ByteArray): ByteArray {
        val s = sps; val p = pps
        if (s == null || p == null) return au
        val out = ByteArray(s.size + p.size + au.size)
        System.arraycopy(s, 0, out, 0, s.size)
        System.arraycopy(p, 0, out, s.size, p.size)
        System.arraycopy(au, 0, out, s.size + p.size, au.size)
        return out
    }

    @Synchronized
    fun attachPreview(surface: Surface) {
        pendingPreview = surface
        compositor?.attachPreview(surface)
    }

    @Synchronized
    fun detachPreview() {
        pendingPreview = null
        compositor?.detachPreview()
    }

    @Synchronized
    fun stop() {
        receiver?.stop(); receiver = null
        deviceCamera?.stop(); deviceCamera = null
        watchdog?.interrupt(); watchdog = null
        decoder?.stop(); decoder = null
        compositor?.release(); compositor = null
        sps = null; pps = null; firstFrameSeen = false; pendingPreview = null
        _stats.value = VideoStats(state = IngestState.IDLE)
    }

    /** Tablet's LAN IPv4 the camera should push to (NetworkInterface — WifiManager is
     *  redacted on 12+). Accepts ANY private/site-local address (192.168.x, 10.x,
     *  172.16–31.x), not just 192.168.x, so the address shows on any field/home Wi-Fi.
     *  Prefers the Wi-Fi interface and excludes cellular (rmnet/100.x is not site-local). */
    private fun localWifiIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }   // Wi-Fi first, then anything else
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) { Log.w(TAG, "ip lookup failed", e); null }

    const val MODE_EXTERNAL = "external"   // a camera pushes RTMP to this tablet
    const val MODE_DEVICE = "device"       // this device's own built-in camera (all-in-one)
    private const val TAG = "RtmpHub"
}

/** Foreground service that keeps [RtmpHub]'s pipeline alive across app backgrounding. */
class RtmpReceiverService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, 1935) ?: 1935
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        RtmpHub.start(port)
        Log.i(TAG, "receiver service started on :$port")
        return START_STICKY
    }

    override fun onDestroy() {
        RtmpHub.stop()
        Log.i(TAG, "receiver service destroyed")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Camera link", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("All-Stars Live")
            .setContentText("Camera link ready — waiting for the Mevo")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RtmpReceiverService"
        private const val CHANNEL = "camera_link"
        private const val NOTIF_ID = 4201
        private const val EXTRA_PORT = "port"

        fun start(ctx: Context, port: Int) {
            val i = Intent(ctx, RtmpReceiverService::class.java).putExtra(EXTRA_PORT, port)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, RtmpReceiverService::class.java))
    }
}
