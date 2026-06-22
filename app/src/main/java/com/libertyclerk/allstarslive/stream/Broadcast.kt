package com.libertyclerk.allstarslive.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.libertyclerk.allstarslive.gl.VideoCompositor
import com.libertyclerk.allstarslive.ingest.RtmpHub
import com.libertyclerk.allstarslive.ingest.RtmpReceiverService
import com.libertyclerk.allstarslive.youtube.YouTubeAuth
import com.libertyclerk.allstarslive.youtube.YouTubeLive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for "are we live on YouTube?". Both the Video tab and the
 * Game-page monitor observe [state]; going live / ending from either screen flows
 * through here. The YouTube streamer attaches to the persistent camera compositor
 * ([RtmpHub.videoCompositor]) so the broadcast keeps running across tab switches —
 * which is exactly why the two screens stay in sync.
 */
object Broadcast {

    enum class Phase { OFFLINE, STARTING, LIVE, ERROR }

    data class State(
        val phase: Phase = Phase.OFFLINE,
        val title: String = "All-Stars Live",
        val privacy: String = "unlisted",
        val videoId: String? = null,
        val watchUrl: String? = null,
        val status: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // App-level "Start game stream" dialog request, so the Video tab button and the
    // Game-page web button both raise the SAME dialog (one UI, one flow).
    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog
    fun requestDialog() { _showDialog.value = true }
    fun dismissDialog() { _showDialog.value = false }

    private const val PROG_W = 1280
    private const val PROG_H = 720
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var streamer: YouTubeStreamer? = null

    /** True once a broadcast is starting or live (so both screens show the LIVE state). */
    val isActive: Boolean get() = _state.value.phase.let { it == Phase.LIVE || it == Phase.STARTING }

    /**
     * Create a YouTube broadcast and push the live camera (+scorebug) to it. Safe to
     * call from any screen; needs the camera pipeline up ([RtmpHub.videoCompositor]).
     */
    fun goLive(context: Context, title: String, privacy: String) {
        if (isActive) return
        // Ensure the camera pipeline is running even if the operator went straight to the
        // Game tab and never opened Video — otherwise there'd be no compositor to stream.
        RtmpReceiverService.start(context, RtmpHub.port)
        if (RtmpHub.videoCompositor == null) RtmpHub.start(RtmpHub.port)
        val comp = RtmpHub.videoCompositor
        if (comp == null) {
            _state.value = State(phase = Phase.ERROR, status = "Camera link not ready — try again")
            return
        }
        _state.value = State(phase = Phase.STARTING, title = title, privacy = privacy, status = "Setting up your YouTube broadcast…")
        YouTubeAuth.client(context).authorize(YouTubeAuth.request())
            .addOnSuccessListener { result ->
                val token = result.accessToken
                if (token == null) {
                    _state.value = _state.value.copy(phase = Phase.ERROR, status = "Connect YouTube in Settings first")
                    return@addOnSuccessListener
                }
                Thread {
                    runCatching { YouTubeLive.startBroadcast(token, title.ifBlank { "All-Stars Live" }, privacy) }
                        .onSuccess { live -> main.post { beginPush(comp, live) } }
                        .onFailure { e ->
                            Log.e(TAG, "broadcast setup failed", e)
                            main.post { _state.value = _state.value.copy(phase = Phase.ERROR, status = "YouTube setup failed: ${e.message}") }
                        }
                }.start()
            }
            .addOnFailureListener { _state.value = _state.value.copy(phase = Phase.ERROR, status = "YouTube sign-in failed: ${it.message}") }
    }

    private fun beginPush(comp: VideoCompositor, live: YouTubeLive.Live) {
        val s = YouTubeStreamer(PROG_W, PROG_H, onStatus = { status ->
            main.post {
                val cur = _state.value
                _state.value = when {
                    status == "LIVE" -> cur.copy(phase = Phase.LIVE, status = "LIVE")
                    status.startsWith("Failed") || status.startsWith("Auth") -> cur.copy(phase = Phase.ERROR, status = status)
                    else -> cur.copy(status = status)
                }
            }
        })
        comp.setEncoderSurface(s.inputSurface, PROG_W, PROG_H) { s.drain() }
        s.start("rtmp://a.rtmp.youtube.com/live2/${live.streamKey}")
        streamer = s
        // Video id is known now, so the monitor can load it even before the encoder fully connects.
        _state.value = _state.value.copy(
            phase = Phase.STARTING,
            videoId = live.broadcastId,
            watchUrl = live.watchUrl,
            status = "Connecting…",
        )
        Log.i(TAG, "pushing to YouTube: ${live.watchUrl}")
    }

    /** End the broadcast — detach the encoder from the (still-running) camera compositor. */
    fun stop() {
        val s = streamer; streamer = null
        val comp = RtmpHub.videoCompositor
        if (comp != null) comp.detachEncoder { s?.stop() } else s?.stop()
        _state.value = State(phase = Phase.OFFLINE)
    }

    private const val TAG = "Broadcast"
}
