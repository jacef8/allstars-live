package com.libertyclerk.allstarslive.ingest

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Editable, persisted camera connection settings — deliberately NOT hardcoded so
 * the app isn't tied to one camera. Today it holds the Mevo's values as defaults;
 * when Camera Profiles land this becomes one profile among several.
 *
 * The Wi-Fi name/password live here (not in code) so changing the camera's
 * password is an in-app edit, not a rebuild. Backed by a Keystore-encrypted
 * SharedPreferences file — this is the one real plaintext-secret-at-rest a security
 * audit found in the app (the camera's Wi-Fi passphrase). Falls back to plain
 * SharedPreferences only if Keystore setup itself fails (rare OEM quirk) so a
 * broken keystore can't take camera setup down with it.
 */
class CameraSettings(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "camera_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w("CameraSettings", "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
        context.getSharedPreferences("camera", Context.MODE_PRIVATE)
    }

    init {
        migrateFromLegacyPlaintextPrefs(context)
    }

    // One-time migration: existing installs have their camera URL/SSID/password in the OLD
    // plaintext "camera" file. If the new encrypted file is still empty, copy those values over
    // once and wipe the plaintext copy — otherwise upgrading this app would silently reset
    // everyone's saved camera Wi-Fi back to the Mevo defaults.
    private fun migrateFromLegacyPlaintextPrefs(context: Context) {
        if (prefs.contains(KEY_URL) || prefs.contains(KEY_SSID) || prefs.contains(KEY_PASS)) return
        val legacy = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
        if (!legacy.contains(KEY_URL) && !legacy.contains(KEY_SSID) && !legacy.contains(KEY_PASS)) return
        prefs.edit()
            .putString(KEY_URL, legacy.getString(KEY_URL, DEFAULT_URL))
            .putString(KEY_SSID, legacy.getString(KEY_SSID, DEFAULT_SSID))
            .putString(KEY_PASS, legacy.getString(KEY_PASS, DEFAULT_PASS))
            .apply()
        legacy.edit().clear().apply()
    }

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
