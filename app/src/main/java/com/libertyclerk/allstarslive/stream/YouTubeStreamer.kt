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
    bitRate: Int = (width * height * 5).coerceAtLeast(2_500_000),
    private val onStatus: (String) -> Unit,
) {
    // True between start() and stop(): while set, a dropped RTMP connection (e.g. the
    // camera glitched and the stream starved → "broken pipe") auto-reconnects instead of
    // ending the broadcast. Cleared by stop() so an intentional end doesn't retry.
    @Volatile private var shouldStream = false

    private val client: RtmpClient = RtmpClient(object : ConnectChecker {
        override fun onConnectionStarted(url: String) = onStatus("Connecting…")
        override fun onConnectionSuccess() {
            requestKeyFrame()        // push an IDR immediately so YouTube re-locks fast
            onStatus("LIVE")
        }
        override fun onConnectionFailed(reason: String) = onConnFailed(reason)
        override fun onNewBitrate(bitrate: Long) {}
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
        audioEncoder.start()
        streaming = true
        shouldStream = true
        client.setReTries(60)          // ~5 min of 5s retries — survive a whole game's hiccups
        startAudio()
        client.connect(rtmpUrl)
    }

    /** Auto-reconnect on a dropped connection (e.g. "broken pipe" after the camera
     *  glitched). Defined as a member fn so it can use [client] at call-time (referencing
     *  it inside client's own initializer would be a recursive/uninitialized error). */
    private fun onConnFailed(reason: String) {
        if (shouldStream && client.shouldRetry(reason)) {
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
            val usPerChunk = chunkSamples * 1_000_000L / sampleRate
            var ptsUs = 0L

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

            try {
                while (streaming) {
                    if (mic != null) {
                        val n = mic.read(pcm, 0, chunkBytes)
                        if (n <= 0) { Thread.sleep(5); continue }
                        feedAudio(pcm, n, ptsUs)
                    } else {
                        java.util.Arrays.fill(pcm, 0)
                        feedAudio(pcm, chunkBytes, ptsUs)
                        Thread.sleep(20)               // pace silence ~real-time
                    }
                    ptsUs += usPerChunk
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

    companion object { private const val TAG = "YouTubeStreamer" }
}
