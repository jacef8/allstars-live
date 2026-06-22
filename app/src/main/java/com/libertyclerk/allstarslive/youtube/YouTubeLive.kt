package com.libertyclerk.allstarslive.youtube

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * M3 (full) slice 2: programmatically set up a YouTube Live broadcast and return
 * its RTMP stream key — so the operator taps Go Live and the app does the rest.
 *
 * Flow (all server-side via the YouTube Data API, with the user's OAuth token):
 *   1. liveStreams.insert  -> a reusable RTMP stream + its ingestion key
 *   2. liveBroadcasts.insert (enableAutoStart, monitor off) -> a broadcast that
 *      goes LIVE automatically once it receives data (no Studio click)
 *   3. liveBroadcasts.bind  -> tie the broadcast to the stream
 * Then [YouTubeStreamer] pushes to rtmp://a.rtmp.youtube.com/live2/<key>.
 */
object YouTubeLive {

    data class Live(val streamKey: String, val broadcastId: String, val watchUrl: String)

    /** Create stream + auto-start broadcast; returns the key + watch URL. Blocking — call off-main. */
    fun startBroadcast(token: String, title: String, privacy: String = "unlisted"): Live {
        // 1) reusable RTMP stream
        val streamBody = JSONObject().apply {
            put("snippet", JSONObject().put("title", title))
            put("cdn", JSONObject().put("frameRate", "variable").put("ingestionType", "rtmp").put("resolution", "variable"))
            put("contentDetails", JSONObject().put("isReusable", true))
        }
        val stream = api(token, "liveStreams?part=snippet,cdn,contentDetails", streamBody)
        val streamId = stream.getString("id")
        val key = stream.getJSONObject("cdn").getJSONObject("ingestionInfo").getString("streamName")

        // 2) broadcast that auto-starts when the stream goes active
        val bcBody = JSONObject().apply {
            put("snippet", JSONObject().put("title", title).put("scheduledStartTime", isoSoon()))
            put("status", JSONObject().put("privacyStatus", privacy).put("selfDeclaredMadeForKids", false))
            put("contentDetails", JSONObject()
                .put("enableAutoStart", true)
                .put("enableAutoStop", true)
                .put("enableEmbed", true)   // allow our in-app monitor to play it (else YouTube error 153/150)
                .put("monitorStream", JSONObject().put("enableMonitorStream", false)))
        }
        val bc = api(token, "liveBroadcasts?part=snippet,status,contentDetails", bcBody)
        val broadcastId = bc.getString("id")

        // 3) bind broadcast <-> stream
        api(token, "liveBroadcasts/bind?id=$broadcastId&part=id,contentDetails&streamId=$streamId", null)

        return Live(key, broadcastId, "https://www.youtube.com/watch?v=$broadcastId")
    }

    /** Broadcast lifecycle: created → ready → (testing) → live → complete. Blocking. */
    fun lifeCycleStatus(token: String, broadcastId: String): String {
        val json = get(token, "liveBroadcasts?part=status&id=$broadcastId")
        val items = json.optJSONArray("items") ?: return ""
        if (items.length() == 0) return ""
        return items.getJSONObject(0).optJSONObject("status")?.optString("lifeCycleStatus") ?: ""
    }

    private fun get(token: String, path: String): JSONObject {
        val conn = (URL("https://www.googleapis.com/youtube/v3/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000; readTimeout = 20000
        }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("YouTube GET ${path.substringBefore('?')} → $code: ${resp.take(180)}")
        return if (resp.isBlank()) JSONObject() else JSONObject(resp)
    }

    private fun api(token: String, path: String, body: JSONObject?): JSONObject {
        val conn = (URL("https://www.googleapis.com/youtube/v3/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000; readTimeout = 20000
            doOutput = true
            outputStream.use { it.write((body?.toString() ?: "").toByteArray()) }
        }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("YouTube ${path.substringBefore('?')} → $code: ${resp.take(220)}")
        return if (resp.isBlank()) JSONObject() else JSONObject(resp)
    }

    private fun isoSoon(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(System.currentTimeMillis() + 8000))   // a few seconds out
    }
}
