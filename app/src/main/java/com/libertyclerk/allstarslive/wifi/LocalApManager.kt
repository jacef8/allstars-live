package com.libertyclerk.allstarslive.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Hosts the tablet's own **local-only Wi-Fi AP** so the cameras (DJI Osmo via Mimo,
 * Mevo Start) can push RTMP straight to us — with **no internet routing**, so the
 * cellular radio stays the device's default route and the YouTube uplink is
 * unaffected. This is the core of the tablet-broadcast pivot: the tablet is the
 * network, not a client on the camera's Wi-Fi.
 *
 * Uses [WifiManager.startLocalOnlyHotspot] (API 26+). SSID + passphrase are
 * system-generated and not freely settable — we surface them (text + QR) so the
 * operator joins each camera once. The AP's own IPv4 (the gateway the cameras push
 * RTMP to, typically 192.168.x.1) is resolved from the AP network interface.
 *
 * Caveats (validate on the real Tab S9 at M0):
 *  - Some Samsung builds bring this up 2.4 GHz only. Two 1080p feeds fit, but
 *    measure throughput. If congested, the Wi-Fi Direct GO fallback is the plan.
 *  - Won't start if the regular tethering hotspot is on.
 *  - Needs location services ON pre-Android-13; NEARBY_WIFI_DEVICES on 13+.
 */
object LocalApManager {

    enum class State { OFF, STARTING, ON, FAILED }

    data class ApInfo(
        val state: State = State.OFF,
        val ssid: String? = null,
        val passphrase: String? = null,
        /** "2.4 GHz" / "5 GHz" / null if unknown. */
        val band: String? = null,
        /** The tablet's IPv4 on the AP — the gateway the cameras push RTMP to. */
        val gatewayIp: String? = null,
        val error: String? = null,
    )

    private val _info = MutableStateFlow(ApInfo())
    val info: StateFlow<ApInfo> = _info

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var bgThread: HandlerThread? = null

    val isOn: Boolean get() = reservation != null

    @Synchronized
    fun start(context: Context) {
        if (reservation != null || _info.value.state == State.STARTING) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        _info.value = ApInfo(state = State.STARTING)

        val thread = HandlerThread("local-ap").also { it.start() }
        bgThread = thread
        val handler = Handler(thread.looper)

        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val (ssid, pass, band) = readConfig(res)
                    // The AP interface needs a moment to come up with its IPv4 — resolve
                    // with a short retry rather than racing it.
                    val ip = resolveGatewayIp(retries = 12, delayMs = 250)
                    Log.i(TAG, "local-only AP up: ssid=$ssid band=$band gateway=$ip")
                    _info.value = ApInfo(
                        state = State.ON,
                        ssid = ssid, passphrase = pass, band = band, gatewayIp = ip,
                    )
                }

                override fun onStopped() {
                    Log.i(TAG, "local-only AP stopped")
                    reservation = null
                    _info.value = ApInfo(state = State.OFF)
                }

                override fun onFailed(reason: Int) {
                    Log.e(TAG, "local-only AP failed: ${reasonText(reason)}")
                    reservation = null
                    _info.value = ApInfo(state = State.FAILED, error = reasonText(reason))
                }
            }, handler)
        } catch (e: SecurityException) {
            // Missing NEARBY_WIFI_DEVICES (13+) or ACCESS_FINE_LOCATION / location off (pre-13).
            _info.value = ApInfo(
                state = State.FAILED,
                error = "Permission needed: allow Nearby devices / Location, and turn Location ON.",
            )
            Log.e(TAG, "startLocalOnlyHotspot SecurityException", e)
        } catch (e: IllegalStateException) {
            // Most commonly: the regular tethering hotspot is already on.
            _info.value = ApInfo(
                state = State.FAILED,
                error = "Turn OFF the normal Wi-Fi hotspot first, then try again.",
            )
            Log.e(TAG, "startLocalOnlyHotspot IllegalStateException", e)
        }
    }

    @Synchronized
    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        bgThread?.quitSafely(); bgThread = null
        _info.value = ApInfo(state = State.OFF)
    }

    /** SSID / passphrase / band — from SoftApConfiguration (30+) or WifiConfiguration (26-29). */
    private fun readConfig(res: WifiManager.LocalOnlyHotspotReservation): Triple<String?, String?, String?> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cfg = res.softApConfiguration
                val ssid = cfg.ssid
                val pass = cfg.passphrase
                val band = when (cfg.band) {
                    1 -> "2.4 GHz"                 // BAND_2GHZ
                    2, 3 -> "5 GHz"                // BAND_5GHZ (3 = 2.4|5)
                    else -> null
                }
                Triple(ssid, pass, band)
            } else {
                @Suppress("DEPRECATION")
                val cfg = res.wifiConfiguration
                Triple(cfg?.SSID, cfg?.preSharedKey, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readConfig failed", e)
            Triple(null, null, null)
        }
    }

    /**
     * The tablet's own IPv4 on the soft-AP interface — what the cameras push RTMP to.
     * The AP interface is typically named ap0 / swlan0 / wlan1 and holds a site-local
     * address ending in .1 (e.g. 192.168.49.1 / 192.168.x.1). We prefer those names and
     * the .1 host, falling back to any non-loopback site-local IPv4 that isn't the main
     * station (wlan0) address.
     */
    private fun resolveGatewayIp(retries: Int, delayMs: Long): String? {
        repeat(retries) {
            apIpv4()?.let { return it }
            try { Thread.sleep(delayMs) } catch (e: InterruptedException) { return null }
        }
        return apIpv4()
    }

    private fun apIpv4(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.asSequence().map { ni.name to it } }
            .filter { (_, addr) -> addr is Inet4Address && addr.isSiteLocalAddress }
            .map { (name, addr) -> name to (addr.hostAddress ?: "") }
            .filter { it.second.isNotEmpty() }
            .toList()

        fun isApName(n: String) =
            n.startsWith("ap") || n.startsWith("swlan") || n == "wlan1" || n.contains("p2p")

        // 1) AP-named interface whose address ends in .1 (the gateway). 2) any AP-named
        // interface. 3) any .1 host. 4) anything site-local.
        candidates.firstOrNull { isApName(it.first) && it.second.endsWith(".1") }?.second
            ?: candidates.firstOrNull { isApName(it.first) }?.second
            ?: candidates.firstOrNull { it.second.endsWith(".1") }?.second
            ?: candidates.firstOrNull()?.second
    } catch (e: Exception) {
        Log.w(TAG, "apIpv4 lookup failed", e)
        null
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "No Wi-Fi channel available (another AP/hotspot may be active)."
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
            "Couldn't start the camera network (generic error)."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "This device can't host a local AP while in its current Wi-Fi mode."
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "Hotspot is disabled by policy. Turn off the normal hotspot, or check restrictions."
        else -> "Camera network failed (code $reason)."
    }

    private const val TAG = "LocalApManager"
}
