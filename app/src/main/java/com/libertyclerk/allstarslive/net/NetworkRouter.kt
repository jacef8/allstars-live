package com.libertyclerk.allstarslive.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection
import java.net.URL

/**
 * Multi-network helper for the "tablet on the Mevo's internet-less Wi-Fi" situation at the field.
 *
 * The camera serves its own Wi-Fi (no internet); the tablet joins it to RECEIVE the RTMP video, but
 * still needs the internet (cellular) for YouTube sign-in / Go Live / scoring sync. Android can hold
 * both networks at once — this routes each kind of traffic to the right one:
 *
 *  - Keeps a CELLULAR network warm + available (requestNetwork) so the internet is reachable even
 *    while the default route is the camera's dead Wi-Fi.
 *  - Tracks whether the ACTIVE network actually has validated internet → drives the in-app warning.
 *  - openConnection() pins an outbound HTTP(S) call to cellular ONLY when the active network has no
 *    internet, so good Wi-Fi still uses Wi-Fi (no needless cell data) but a dead Wi-Fi falls to cell.
 *
 * We deliberately do NOT bindProcessToNetwork(): the RTMP receiver listens on the wildcard address,
 * and a process-wide rebind could stop it receiving the camera over Wi-Fi. Pinning per-connection is
 * surgical and leaves ingest alone.
 */
object NetworkRouter {
    private const val TAG = "NetworkRouter"
    private var cm: ConnectivityManager? = null

    @Volatile
    var cellular: Network? = null
        private set

    private val _noInternet = MutableStateFlow(false)
    /** True when the device's ACTIVE/default network has no validated internet (e.g. the Mevo Wi-Fi). */
    val noInternet: StateFlow<Boolean> = _noInternet

    private var cellCb: ConnectivityManager.NetworkCallback? = null
    private var defCb: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        if (cm != null) return
        val c = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm = c

        // Hold a cellular network up + available so the internet stays reachable even when the
        // default route is a no-internet Wi-Fi. (Does NOT move normal Wi-Fi traffic onto cellular.)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cellCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cellular = network; Log.i(TAG, "cellular available") }
            override fun onLost(network: Network) { if (cellular == network) cellular = null }
        }
        runCatching { c.requestNetwork(req, cellCb!!) }.onFailure { Log.w(TAG, "requestNetwork(cellular) failed", it) }

        // Watch the ACTIVE network: no VALIDATED internet → surface the warning.
        defCb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _noInternet.value = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            override fun onLost(network: Network) { _noInternet.value = true }
        }
        runCatching { c.registerDefaultNetworkCallback(defCb!!) }.onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    /**
     * Open an HTTP(S) connection for an internet call that MUST succeed (YouTube). When the active
     * network has no internet but cellular is available, pin the call to cellular; otherwise use the
     * default network (so normal Wi-Fi is used when it works). Always falls back gracefully.
     */
    fun openConnection(url: URL): HttpURLConnection {
        val cell = cellular
        return try {
            if (_noInternet.value && cell != null) cell.openConnection(url) as HttpURLConnection
            else url.openConnection() as HttpURLConnection
        } catch (e: Exception) {
            url.openConnection() as HttpURLConnection
        }
    }
}
