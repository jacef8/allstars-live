package com.libertyclerk.allstarslive.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import com.libertyclerk.allstarslive.ingest.RtmpHub
import com.libertyclerk.allstarslive.net.NetworkRouter
import java.nio.ByteBuffer

/**
 * Pushes the composited program frame to YouTube Live over RTMP. The
 * [com.libertyclerk.allstarslive.gl.VideoCompositor] draws each frame into
 * [inputSurface] (our H.264 encoder's input); [drain] (called on the compositor's GL
 * thread after every frame) pulls the encoded NALUs and hands them to RootEncoder's
 * [RtmpClient].
 *
 * Audio: YouTube Live will NOT transition a broadcast to "live" without an audio
 * track (the stream just sits at "upcoming"), so we always send AAC — from the mic
 * when RECORD_AUDIO is granted (real game sound), otherwise silence as a fallback.
 * YouTube URL = rtmp://a.rtmp.youtube.com/live2/<stream-key>.
 */
class YouTubeStreamer(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    // YouTube's own recommended encoding bitrate for 720p30 tops out around 4 Mbps assuming a
    // solid connection — but this app is routinely used over cellular at the field (see
    // NetworkRouter.bindProcessToCellular, added when the camera's own Wi-Fi has no internet at
    // all), where rural/weak-signal LTE upload is often well under that. The old width*height*5
    // formula (~4.6 Mbps at 720p) had nowhere to go but choke once actually pushed over cellular.
    // (jford, 2026-07-06: "the YouTube stream is very choppy and does almost nothing but
    // buffer.") 2.5 Mbps is YouTube's own suggested value for 720p30 and leaves real headroom
    // on a mediocre uplink; MIN_BIT_RATE below is the floor the adaptive step-down (onNewBitrate)
    // is allowed to fall to before it's not worth calling it "live" anymore.
    bitRate: Int = (width * height * 3).coerceIn(MIN_BIT_RATE, 2_500_000),
    private val onStatus: (String) -> Unit,
) {
    // The bitrate this stream STARTED at — the adaptive step-down (onNewBitrate) is only ever
    // allowed to recover back UP to this ceiling, never past it (RootEncoder can suggest a bitrate
    // above what we asked for once a squeeze clears; there's no reason to exceed our own target).
    private val bitRateCeiling = bitRate

    // True between start() and stop(): while set, a dropped RTMP connection (e.g. the
    // camera glitched and the stream starved → "broken pipe") auto-reconnects instead of
    // ending the broadcast. Cleared by stop() so an intentional end doesn't retry.
    @Volatile private var shouldStream = false

    // SHARED audio/video timeline origin. Both the audio PTS (below) and the video PTS (the
    // compositor, via setEncoderSurface(..., avBaseNs, ...)) are zero-based to THIS instant, so the
    // two streams share one clock and stay in lip-sync. (Previously audio was sample-counted from the
    // audio thread's start while video was wall-clock from the first frame → they drifted apart.)
    val avBaseNs: Long = System.nanoTime()

    // When true, broadcast SILENCE instead of the mic (YouTube still needs an audio track, so we
    // keep sending silent AAC). Toggled live via Broadcast.setMuted.
    @Volatile var muted = false

    // CAMERA-AUDIO PASSTHROUGH: when true, send the external camera's AAC straight through (no mic,
    // no re-encode) instead of capturing the tablet mic. Set before start(). Falls back to mic if the
    // camera delivers no audio.
    @Volatile var cameraAudio = false
    @Volatile private var camAudioConfigured = false
    @Volatile private var camSampleRate = 44100

    // Same anchor-then-advance-by-samples pattern as the mic path below (see the comment on
    // samplesWritten/firstPtsUs in startAudio) — the camera's AAC arrives over Wi-Fi/RTMP, so it's
    // even more exposed to scheduling/network jitter than the local mic ever was. Re-deriving PTS
    // from wall-clock per frame here caused the "garbled/bucket" audio on camera passthrough.
    private var camSamplesWritten = 0L
    private var camFirstPtsUs = -1L

    private val client: RtmpClient = RtmpClient(object : ConnectChecker {
        override fun onConnectionStarted(url: String) = onStatus("Connecting…")
        override fun onConnectionSuccess() {
            NetworkRouter.unbindProcess()   // connect attempt resolved — see bindProcessToCellular's own comment
            requestKeyFrame()        // push an IDR immediately so YouTube re-locks fast
            onStatus("LIVE")
        }
        override fun onConnectionFailed(reason: String) {
            NetworkRouter.unbindProcess()   // this attempt is over, successful or not — always clear the bind
            onConnFailed(reason)
        }
        // RootEncoder watches actual RTMP send throughput and periodically suggests a bitrate that
        // roughly matches what the connection can currently sustain — this used to be silently
        // discarded, so a stream that started fine but hit a bandwidth dip (a routine reality on
        // the cellular fallback used at the field — see NetworkRouter.bindProcessToCellular) just
        // kept trying to push the SAME too-high bitrate forever, backing up an ever-growing send
        // queue that shows up to viewers as "does almost nothing but buffer." (jford, 2026-07-06.)
        // Applying it live to the already-running MediaCodec encoder (no restart/reconnect needed)
        // lets quality step down under a real squeeze and step back up once it clears.
        override fun onNewBitrate(bitrate: Long) {
            val clamped = bitrate.coerceIn(MIN_BIT_RATE.toLong(), bitRateCeiling.toLong()).toInt()
            runCatching { encoder.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped) }) }
        }
        override fun onDisconnect() { if (!shouldStream) onStatus("Stopped") }
        override fun onAuthError() = onStatus("Auth error — check the stream key")
        override fun onAuthSuccess() {}
    })

    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    /** The surface the compositor draws the program frame into. */
    val inputSurface: Surface
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var streaming = false
    @Volatile private var sentConfig = false

    // ---- audio ----
    private val sampleRate = 44100
    private val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val audioInfo = MediaCodec.BufferInfo()
    private var audioThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()   // must precede start()

        val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        audioEncoder.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        client.setVideoResolution(width, height)
        client.setFps(fps)
        client.setAudioInfo(sampleRate, false)   // mono — and tells the client we DO have audio
    }

    /** Start encoding and connect. [rtmpUrl] = rtmp://a.rtmp.youtube.com/live2/<key> */
    fun start(rtmpUrl: String) {
        encoder.start()
        streaming = true
        shouldStream = true
        client.setReTries(60)          // ~5 min of 5s retries — survive a whole game's hiccups
        if (cameraAudio) startCameraAudio() else { audioEncoder.start(); startAudio() }
        // See NetworkRouter.bindProcessToCellular's comment: RootEncoder's RtmpClient opens its own
        // socket with no way to hand it a specific Network, so on the "tablet is on the camera's
        // internet-less Wi-Fi" setup this connect can silently try (and fail) over that dead Wi-Fi
        // instead of falling back to cellular. Bind for just this handshake; unbindProcess() fires
        // from the ConnectChecker callbacks above the instant the outcome is known.
        NetworkRouter.bindProcessToCellular()
        client.connect(rtmpUrl)
    }

    // ---- camera-audio passthrough: forward the camera's AAC straight to YouTube ----
    private fun startCameraAudio() {
        camSamplesWritten = 0L
        camFirstPtsUs = -1L
        RtmpHub.camAudioConfig?.let { applyCameraAsc(it) }           // config the client from the known ASC
        RtmpHub.onCamAudioConfig = { asc -> applyCameraAsc(asc) }    // and update if it (re)arrives
        RtmpHub.onCamAudio = { aac, _ -> feedCameraAac(aac) }
        Log.i(TAG, "audio: CAMERA passthrough (configured=$camAudioConfigured)")
    }
    private fun applyCameraAsc(asc: ByteArray) {
        if (asc.size < 2) return
        val sfi = ((asc[0].toInt() and 0x07) shl 1) or ((asc[1].toInt() shr 7) and 0x01)   // sampling-freq index
        val ch = (asc[1].toInt() shr 3) and 0x0F                                            // channel config
        val rate = AAC_RATES.getOrElse(sfi) { 44100 }
        runCatching { client.setAudioInfo(rate, ch >= 2) }
        camSampleRate = rate
        camAudioConfigured = true
        Log.i(TAG, "camera audio: ${rate}Hz ch=$ch")
    }
    private fun feedCameraAac(aac: ByteArray) {
        if (!streaming || !camAudioConfigured) return
        // Anchor the first frame to the shared a/v clock once, then advance strictly by samples
        // written (1024 samples/frame, standard AAC-LC) — NOT wall-clock per frame. See the
        // comment on camSamplesWritten above and the matching mic-path fix in startAudio().
        if (camFirstPtsUs < 0L) camFirstPtsUs = (System.nanoTime() - avBaseNs) / 1000 + AUDIO_DELAY_US
        val pts = camFirstPtsUs + camSamplesWritten * 1_000_000L / camSampleRate
        camSamplesWritten += 1024L
        val info = MediaCodec.BufferInfo().apply { set(0, aac.size, pts, 0) }
        runCatching { client.sendAudio(ByteBuffer.wrap(aac), info) }
    }

    /** Auto-reconnect on a dropped connection (e.g. "broken pipe" after the camera
     *  glitched). Defined as a member fn so it can use [client] at call-time (referencing
     *  it inside client's own initializer would be a recursive/uninitialized error). */
    private fun onConnFailed(reason: String) {
        if (shouldStream && client.shouldRetry(reason)) {
            // reConnect() will open a fresh socket in ~5s — re-bind now so THAT attempt also goes out
            // over cellular instead of whatever's default (the unbind above cleared it after the
            // attempt that just failed).
            NetworkRouter.bindProcessToCellular()
            client.reConnect(5000)
            onStatus("Reconnecting…")
        } else {
            onStatus("Failed: $reason")
        }
    }

    /** Ask the encoder for an immediate keyframe (so a fresh/reconnected RTMP session
     *  gets an IDR right away instead of waiting up to the 2s GOP). */
    private fun requestKeyFrame() {
        runCatching {
            encoder.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    /** Pull encoded VIDEO frames and send them. Runs on the compositor's GL thread. */
    fun drain() {
        if (!streaming) return
        while (true) {
            val i = try { encoder.dequeueOutputBuffer(bufferInfo, 0) } catch (e: IllegalStateException) { return }
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = encoder.outputFormat
                    val sps = fmt.getByteBuffer("csd-0")
                    val pps = fmt.getByteBuffer("csd-1")
                    if (sps != null && pps != null) {
                        client.setVideoInfo(sps, pps, null)   // AVC: no VPS
                        sentConfig = true
                    }
                }
                i >= 0 -> {
                    val buf = encoder.getOutputBuffer(i)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && bufferInfo.size > 0 && sentConfig && !isConfig) {
                        runCatching { client.sendVideo(buf, bufferInfo) }
                    }
                    encoder.releaseOutputBuffer(i, false)
                }
            }
        }
    }

    /** Capture audio (mic, or silence if unavailable) -> AAC -> RTMP, on its own thread. */
    @SuppressLint("MissingPermission")
    private fun startAudio() {
        audioThread = Thread {
            val chunkSamples = 1024
            val chunkBytes = chunkSamples * 2          // 16-bit mono
            val pcm = ByteArray(chunkBytes)

            // Try the mic; fall back to silence (so YouTube still gets an audio track).
            val mic = runCatching {
                val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, chunkBytes * 4),
                ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
            mic?.runCatching { startRecording() }
            Log.i(TAG, if (mic != null) "audio: mic" else "audio: silence")

            // Audio PTS MUST advance by SAMPLE COUNT, not wall clock. Per-chunk wall-clock timestamps
            // (the old "shared a/v clock" approach) pick up thread-scheduling jitter, so consecutive
            // 23ms AAC frames get unevenly spaced PTS — the decoder time-warps them into garbled,
            // "screaming-in-a-bucket" noise, worst on quiet audio. Instead: anchor the FIRST frame to the
            // shared a/v clock once (keeps audio/video in sync), then advance strictly by samples written.
            var samplesWritten = 0L
            var firstPtsUs = -1L
            try {
                while (streaming) {
                    if (firstPtsUs < 0L) firstPtsUs = (System.nanoTime() - avBaseNs) / 1000
                    val ptsUs = firstPtsUs + samplesWritten * 1_000_000L / sampleRate
                    if (mic != null && !muted) {
                        val n = mic.read(pcm, 0, chunkBytes)
                        if (n <= 0) { Thread.sleep(5); continue }
                        feedAudio(pcm, n, ptsUs)
                        samplesWritten += (n / 2).toLong()        // 16-bit mono → 2 bytes per sample
                    } else {                          // muted, or no mic → silent AAC (keeps the track alive)
                        java.util.Arrays.fill(pcm, 0)
                        feedAudio(pcm, chunkBytes, ptsUs)
                        samplesWritten += chunkSamples.toLong()
                        Thread.sleep(20)               // pace silence ~real-time
                    }
                    drainAudio()
                }
            } catch (_: InterruptedException) {
            } finally {
                mic?.runCatching { stop() }
                mic?.runCatching { release() }
            }
        }.also { it.start() }
    }

    private fun feedAudio(data: ByteArray, len: Int, ptsUs: Long) {
        val idx = try { audioEncoder.dequeueInputBuffer(10_000) } catch (e: IllegalStateException) { return }
        if (idx >= 0) {
            val ib = audioEncoder.getInputBuffer(idx) ?: return
            ib.clear(); ib.put(data, 0, len)
            audioEncoder.queueInputBuffer(idx, 0, len, ptsUs, 0)
        }
    }

    private fun drainAudio() {
        while (true) {
            val i = try { audioEncoder.dequeueOutputBuffer(audioInfo, 0) } catch (e: IllegalStateException) { return }
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                i >= 0 -> {
                    val buf = audioEncoder.getOutputBuffer(i)
                    val isConfig = audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && audioInfo.size > 0 && !isConfig) {
                        runCatching { client.sendAudio(buf, audioInfo) }
                    }
                    audioEncoder.releaseOutputBuffer(i, false)
                }
            }
        }
    }

    /** Stop streaming and release encoders. Call on the compositor GL thread (after detach). */
    fun stop() {
        streaming = false
        shouldStream = false        // intentional end — don't auto-reconnect
        NetworkRouter.unbindProcess()   // defensive: in case stop() lands while a connect/reconnect is still in flight
        if (cameraAudio) { RtmpHub.onCamAudio = null; RtmpHub.onCamAudioConfig = null; camFirstPtsUs = -1L; camSamplesWritten = 0L }   // stop forwarding camera audio
        audioThread?.interrupt(); audioThread = null
        runCatching { client.disconnect() }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { audioEncoder.stop() }
        runCatching { audioEncoder.release() }
        runCatching { inputSurface.release() }
        Log.i(TAG, "stream stopped")
    }

    val isStreaming: Boolean get() = streaming

    companion object {
        private const val TAG = "YouTubeStreamer"
        // Tunable a/v offset for camera-audio passthrough (µs added to audio PTS). 0 = no shift; bump
        // it positive if audio runs ahead of the (decode+composite-delayed) video on real hardware.
        private const val AUDIO_DELAY_US = 0L
        // AAC sampling-frequency-index → Hz (from the AudioSpecificConfig).
        private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        /** Floor for the adaptive step-down in onNewBitrate — under this, "live" isn't worth much anyway. */
        private const val MIN_BIT_RATE = 500_000
    }
}
