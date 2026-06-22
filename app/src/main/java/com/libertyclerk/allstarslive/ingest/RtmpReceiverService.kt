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

    /** The compositor, once the pipeline is up — for encoder (stream/record) + overlay. */
    val videoCompositor: VideoCompositor? get() = compositor

    private var sps: ByteArray? = null   // Annex-B (from the AVC config record)
    private var pps: ByteArray? = null
    @Volatile private var firstFrameSeen = false
    private var pendingPreview: Surface? = null

    val isRunning: Boolean get() = receiver != null

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
    }

    private fun onVideo(au: ByteArray, ptsMs: Long, keyframe: Boolean) {
        if (!firstFrameSeen) {
            firstFrameSeen = true
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
        decoder?.stop(); decoder = null
        compositor?.release(); compositor = null
        sps = null; pps = null; firstFrameSeen = false; pendingPreview = null
        _stats.value = VideoStats(state = IngestState.IDLE)
    }

    /** Tablet IPv4 on the Wi-Fi (NetworkInterface — WifiManager is redacted on 12+). */
    private fun localWifiIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { it.startsWith("192.168.") }
    } catch (e: Exception) { Log.w(TAG, "ip lookup failed", e); null }

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
