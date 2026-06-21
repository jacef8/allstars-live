package com.libertyclerk.allstarslive.ingest

import android.content.Context

/**
 * Editable, persisted camera connection settings — deliberately NOT hardcoded so
 * the app isn't tied to one camera. Today it holds the Mevo's values as defaults;
 * when Camera Profiles land this becomes one profile among several.
 *
 * The Wi-Fi name/password live here (not in code) so changing the camera's
 * password is an in-app edit, not a rebuild. Backed by SharedPreferences.
 */
class CameraSettings(context: Context) {
    private val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(v) = prefs.edit().putString(KEY_URL, v).apply()

    var wifiSsid: String
        get() = prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID
        set(v) = prefs.edit().putString(KEY_SSID, v).apply()

    var wifiPassphrase: String
        get() = prefs.getString(KEY_PASS, DEFAULT_PASS) ?: DEFAULT_PASS
        set(v) = prefs.edit().putString(KEY_PASS, v).apply()

    companion object {
        // Known-good Mevo defaults; overwritten by whatever the operator enters.
        const val DEFAULT_URL = "srt://192.168.17.1:4201"
        const val DEFAULT_SSID = "Mevo-2DDTR"
        const val DEFAULT_PASS = "12345678"

        private const val KEY_URL = "url"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASS = "pass"
    }
}
