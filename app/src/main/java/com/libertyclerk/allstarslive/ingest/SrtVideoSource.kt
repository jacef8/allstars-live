package com.libertyclerk.allstarslive.ingest

import android.content.Context
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Real SRT [VideoSource] for the libsrt + NDK route.
 *
 *   native libsrt recv + MPEG-TS demux (app/src/main/cpp)
 *     -> [onAccessUnit] -> [MediaCodecVideoDecoder] -> Surface
 *
 * The Mevo serves SRT on its own Wi-Fi, which has no internet — so Android keeps
 * cellular as the default network (good: YouTube goes out cellular) and will even
 * drop the Wi-Fi if left alone. We therefore *request* the Wi-Fi transport
 * explicitly (internet not required) to hold it up, then bind this process's
 * sockets to it so libsrt's UDP reaches the camera. Cellular stays available for
 * the outbound stream.
 */
class SrtVideoSource(private val context: Context) : VideoSource {

    private val _stats = MutableStateFlow(VideoStats())
    override val stats: StateFlow<VideoStats> = _stats

    private var decoder: MediaCodecVideoDecoder? = null
    private var nativeHandle: Long = 0L
    @Volatile private var firstFrameSeen = false

    private val cm by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingUrl: String? = null

    /**
     * Camera Wi-Fi to join on [start]. When [wifiSsid] is set (and the OS supports
     * it), the app connects to that AP itself via WifiNetworkSpecifier — no manual
     * Wi-Fi switching, and Android won't auto-drop it for lack of internet. Blank
     * SSID falls back to binding whatever Wi-Fi the tablet is already on.
     */
    var wifiSsid: String = ""
    var wifiPassphrase: String = ""

    override fun start(url: String, surface: Surface) {
        if (nativeHandle != 0L) return
        firstFrameSeen = false
        pendingUrl = url

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

        val ssid = wifiSsid.trim()
        if (ssid.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            joinCameraWifi(ssid, wifiPassphrase)
        } else {
            useExistingWifi()
        }
    }

    /**
     * Bring up the camera's Wi-Fi ourselves. Android shows a one-time approval
     * dialog the first time per SSID; after that it keeps the network up *for this
     * app* (bound, internet-less, never auto-dropped) until [stop].
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun joinCameraWifi(ssid: String, passphrase: String) {
        _stats.value = VideoStats(state = IngestState.CONNECTING, message = "joining $ssid…")
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply { if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase) }
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = beginSrt(network)
            override fun onUnavailable() {
                if (nativeHandle == 0L) _stats.value = _stats.value.copy(
                    state = IngestState.ERROR,
                    message = "Couldn't join \"$ssid\" — check the camera Wi-Fi name & password",
                )
            }
        }
        netCallback = cb
        try {
            cm.requestNetwork(req, cb)   // no timeout: hold the camera Wi-Fi up for the app
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork (specifier) failed", e)
            _stats.value = _stats.value.copy(state = IngestState.ERROR, message = "Wi-Fi request failed")
        }
    }

    /** Legacy path: hold/bind whatever Wi-Fi the tablet is already connected to. */
    private fun useExistingWifi() {
        _stats.value = VideoStats(state = IngestState.CONNECTING, message = "finding camera Wi-Fi…")
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = beginSrt(network)

            override fun onUnavailable() {
                // Samsung intermittently won't hand back an internet-less Wi-Fi via
                // requestNetwork even while the tablet is plainly connected to it.
                // Fall back to the Wi-Fi the system already holds.
                val existing = existingWifiNetwork()
                if (existing != null) {
                    Log.i(TAG, "requestNetwork onUnavailable — using already-connected Wi-Fi")
                    beginSrt(existing)
                } else if (nativeHandle == 0L) {
                    _stats.value = _stats.value.copy(
                        state = IngestState.ERROR,
                        message = "No Wi-Fi found — connect the tablet to the Mevo's Wi-Fi",
                    )
                }
            }
        }
        netCallback = cb
        try {
            cm.requestNetwork(req, cb, 10_000)   // up to 10s to grab/hold the Mevo Wi-Fi
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork failed", e)
        }
        // Don't wait on the (flaky) callback if the tablet is already on the Mevo's
        // Wi-Fi — bind to it and start SRT immediately. beginSrt is idempotent.
        existingWifiNetwork()?.let { beginSrt(it) }
    }

    /** Bind this process to [network] and start the native SRT receiver. Idempotent. */
    @Synchronized
    private fun beginSrt(network: Network) {
        if (nativeHandle != 0L) return
        val u = pendingUrl ?: return
        try {
            cm.bindProcessToNetwork(network)
            Log.i(TAG, "bound process to Wi-Fi; starting SRT")
        } catch (e: Exception) {
            Log.e(TAG, "bindProcessToNetwork failed", e)
        }
        nativeHandle = nativeStart(u)
        if (nativeHandle == 0L) {
            _stats.value = _stats.value.copy(state = IngestState.ERROR, message = "native start failed")
        }
    }

    /** The Wi-Fi network the system currently holds, if any (regardless of internet). */
    private fun existingWifiNetwork(): Network? =
        cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    override fun stop() {
        val handle = nativeHandle
        nativeHandle = 0L
        pendingUrl = null
        if (handle != 0L) nativeStop(handle)
        decoder?.stop()
        decoder = null
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null
        runCatching { cm.bindProcessToNetwork(null) }   // restore normal routing (cellular)
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
        val mapped = IngestState.entries.getOrElse(state) { IngestState.ERROR }
        _stats.value = _stats.value.copy(state = mapped, message = message)
    }

    private external fun nativeStart(url: String): Long
    private external fun nativeStop(handle: Long)

    companion object {
        private const val TAG = "SrtVideoSource"
        init { System.loadLibrary("srtjni") }
    }
}
