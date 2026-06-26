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

    // Confirm before ending a live broadcast (so it's not killed mid-game by accident).
    private val _showStopConfirm = MutableStateFlow(false)
    val showStopConfirm: StateFlow<Boolean> = _showStopConfirm
    fun requestStop() { if (isActive) _showStopConfirm.value = true }
    fun dismissStop() { _showStopConfirm.value = false }

    // Broadcast-audio mute (sends silence to YouTube). Sticky across broadcasts in this session; the
    // web persists the preference and re-applies it. Takes effect live on the running stream.
    @Volatile var muted = false
        private set
    fun setMuted(b: Boolean) { muted = b; streamer?.muted = b }

    // Use the external camera's audio (passthrough) instead of the tablet mic. Applied at the next
    // go-live (and only when the camera is actually sending audio); default off for safety.
    @Volatile var useCameraAudio = false
        private set
    fun setUseCameraAudio(b: Boolean) { useCameraAudio = b }

    private const val PROG_W = 1280
    private const val PROG_H = 720
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var streamer: YouTubeStreamer? = null

    // ---- local recording (fallback when streaming isn't possible) ----
    data class RecordState(
        val recording: Boolean = false,
        val startedAt: Long = 0L,
        val savedLocation: String = "",
        val error: String = "",
    )
    private val _recState = MutableStateFlow(RecordState())
    val recState: StateFlow<RecordState> = _recState
    @Volatile private var recorder: LocalRecorder? = null
    val isRecording: Boolean get() = recorder != null

    /** True once a broadcast is starting or live (so both screens show the LIVE state). */
    val isActive: Boolean get() = _state.value.phase.let { it == Phase.LIVE || it == Phase.STARTING }

    /**
     * Record the composited program frame (camera + scorebug + mic) to a local MP4 — the
     * offline fallback. Record-only: it shares the one encoder surface with streaming, so we
     * never run both. Stops cleanly via [stopRecording].
     */
    fun startRecording(context: Context) {
        if (recorder != null) return
        if (isActive) { _recState.value = RecordState(error = "Stop the YouTube broadcast first"); return }
        RtmpHub.ensureStarted(context)
        val comp = RtmpHub.videoCompositor
        if (comp == null) { _recState.value = RecordState(error = "Camera link not ready — try again"); return }
        if (!RtmpHub.hasVideo) { _recState.value = RecordState(error = "No camera yet — connect the camera, then record"); return }
        val rec = try {
            LocalRecorder(context, PROG_W, PROG_H, onStatus = {})
        } catch (e: Exception) {
            Log.e(TAG, "recorder init failed", e)
            _recState.value = RecordState(error = "Couldn't start recording: ${e.message}")
            return
        }
        recorder = rec
        comp.setEncoderSurface(rec.inputSurface, PROG_W, PROG_H) { rec.drain() }
        rec.start()
        _recState.value = RecordState(recording = true, startedAt = System.currentTimeMillis())
        Log.i(TAG, "local recording started")
    }

    /** Stop recording and finalize the MP4 (detach first, then stop on the GL thread). */
    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        val where = rec.savedLocation
        val comp = RtmpHub.videoCompositor
        if (comp != null) comp.detachEncoder { rec.stop() } else rec.stop()
        _recState.value = RecordState(recording = false, savedLocation = where)
        Log.i(TAG, "local recording stopped → $where")
    }

    /**
     * Create a YouTube broadcast and push the live camera (+scorebug) to it. Safe to
     * call from any screen; needs the camera pipeline up ([RtmpHub.videoCompositor]).
     */
    fun goLive(context: Context, title: String, privacy: String) {
        if (isActive) return
        if (recorder != null) stopRecording()   // recording shares the encoder surface — going live supersedes it
        // Ensure the camera pipeline is running even if the operator went straight to the
        // Game tab and never opened Video — otherwise there'd be no compositor to stream.
        // Mode-aware: device-camera (all-in-one) vs an external camera pushing RTMP to us.
        RtmpHub.ensureStarted(context)
        val comp = RtmpHub.videoCompositor
        if (comp == null) {
            _state.value = State(phase = Phase.ERROR, status = "Camera link not ready — try again")
            return
        }
        // Don't start a broadcast with no picture — that's what left dead "upcoming"
        // broadcasts on YouTube. Require the camera to actually be delivering frames.
        if (!RtmpHub.hasVideo) {
            _state.value = State(phase = Phase.ERROR, status = "No camera yet — connect the camera, then Go Live")
            return
        }
        _state.value = State(phase = Phase.STARTING, title = title, privacy = privacy, status = "Setting up your YouTube broadcast…")
        // Watchdog: if creation hasn't moved past "Setting up…" within 30s (no internet, YouTube
        // unreachable, or the auth token never returns) surface a clear error instead of hanging.
        main.postDelayed({
            if (_state.value.phase == Phase.STARTING && _state.value.status.startsWith("Setting up")) {
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    status = "Couldn't reach YouTube — check this device has internet and that YouTube is connected, then try Go Live again.",
                )
            }
        }, 30_000L)
        YouTubeAuth.client(context).authorize(YouTubeAuth.request())
            .addOnSuccessListener { result ->
                val token = result.accessToken
                if (token == null) {
                    _state.value = _state.value.copy(phase = Phase.ERROR, status = "Connect YouTube in Settings first")
                    return@addOnSuccessListener
                }
                Thread {
                    runCatching { YouTubeLive.startBroadcast(token, title.ifBlank { "All-Stars Live" }, privacy) }
                        .onSuccess { live -> main.post { beginPush(comp, live, token) } }
                        .onFailure { e ->
                            Log.e(TAG, "broadcast setup failed", e)
                            main.post { _state.value = _state.value.copy(phase = Phase.ERROR, status = "YouTube setup failed: ${e.message}") }
                        }
                }.start()
            }
            .addOnFailureListener { _state.value = _state.value.copy(phase = Phase.ERROR, status = "YouTube sign-in failed: ${it.message}") }
    }

    private fun beginPush(comp: VideoCompositor, live: YouTubeLive.Live, token: String) {
        val s = YouTubeStreamer(PROG_W, PROG_H, onStatus = { status ->
            main.post {
                val cur = _state.value
                _state.value = when {
                    // RTMP connected — but DON'T flip to LIVE yet; YouTube takes a bit to
                    // actually transition the broadcast. The poll below flips us to LIVE so
                    // the monitor doesn't embed early (that's the error-153 flash).
                    status == "LIVE" -> cur.copy(status = "Streaming — waiting for YouTube…")
                    status.startsWith("Failed") || status.startsWith("Auth") -> cur.copy(phase = Phase.ERROR, status = status)
                    else -> cur.copy(status = status)
                }
            }
        })
        s.muted = muted                                                                    // carry the mute preference into this broadcast
        s.cameraAudio = useCameraAudio && RtmpHub.camHasAudio                               // camera audio only if it's actually arriving
        comp.setEncoderSurface(s.inputSurface, PROG_W, PROG_H, s.avBaseNs) { s.drain() }   // shared a/v clock → lip-sync
        s.start("rtmp://a.rtmp.youtube.com/live2/${live.streamKey}")
        streamer = s
        _state.value = _state.value.copy(
            phase = Phase.STARTING,
            videoId = live.broadcastId,
            watchUrl = live.watchUrl,
            status = "Connecting…",
        )
        Log.i(TAG, "pushing to YouTube: ${live.watchUrl}")

        // Poll YouTube until the broadcast is actually live, THEN embed in the monitor.
        // Keep polling for as long as we're active (don't give up after a fixed window —
        // YouTube can sit in "liveStarting" for a while, and we must reflect the real
        // state whenever it flips). If it lingers in liveStarting/testing, nudge it with
        // an explicit transition (harmless if autoStart already handled it).
        Thread {
            var nudged = false
            var lingering = 0
            while (isActive) {
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
                val st = runCatching { YouTubeLive.lifeCycleStatus(token, live.broadcastId) }.getOrNull() ?: continue
                Log.i(TAG, "broadcast lifeCycle=$st")
                when (st) {
                    "live" -> { main.post { if (isActive) _state.value = _state.value.copy(phase = Phase.LIVE, status = "LIVE") }; return@Thread }
                    "complete", "revoked" -> { main.post { _state.value = State(phase = Phase.OFFLINE) }; return@Thread }
                    "testing", "liveStarting", "ready" -> {
                        lingering++
                        main.post { if (isActive) _state.value = _state.value.copy(status = "YouTube is starting the broadcast…") }
                        // After ~15s stuck, explicitly ask YouTube to go live (once).
                        if (!nudged && lingering >= 5) {
                            nudged = true
                            runCatching { YouTubeLive.transition(token, live.broadcastId, "live") }
                                .onFailure { Log.w(TAG, "transition nudge: ${it.message}") }
                        }
                    }
                }
            }
        }.start()
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
