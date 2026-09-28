package com.libertyclerk.allstarslive.youtube

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * M3 (full): Google authorization for the YouTube Live API.
 *
 * Uses the Google Identity Authorization API (play-services-auth) to get an OAuth
 * access token for the YouTube scope, matched to the Android OAuth client
 * (package + SHA-1) configured in Google Cloud. The token is then a Bearer for the
 * YouTube Data API REST endpoints (read channel now; create live broadcast next).
 */
object YouTubeAuth {

    // "manage your YouTube account" — needed to create/transition live broadcasts later.
    private val SCOPE = Scope("https://www.googleapis.com/auth/youtube")

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    fun client(context: Context) = Identity.getAuthorizationClient(context)

    /**
     * Blocking REST call — run OFF the main thread. Returns the signed-in channel's
     * title (proves the token + API access work end-to-end), or throws on error.
     */
    fun fetchChannelTitle(accessToken: String): String {
        val url = URL("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 15000
            readTimeout = 15000
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("YouTube API $code: ${body.take(160)}")
        val items = JSONObject(body).optJSONArray("items")
        if (items == null || items.length() == 0) return "your channel (no channel on this account?)"
        return items.getJSONObject(0).getJSONObject("snippet").getString("title")
    }
}
