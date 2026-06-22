package com.libertyclerk.allstarslive.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient

/**
 * M3 (slice 1, video-only): pushes the composited program frame to YouTube Live
 * over RTMP. The [com.libertyclerk.allstarslive.gl.VideoCompositor] draws each
 * frame into [inputSurface] (our H.264 encoder's input); [drain] (called on the
 * compositor's GL thread after every frame) pulls the encoded NALUs and hands them
 * to RootEncoder's [RtmpClient], which talks RTMP to YouTube.
 *
 * Audio (mic -> AAC) is the next slice; until then we stream video-only
 * (setOnlyVideo(true)). YouTube URL = rtmp://a.rtmp.youtube.com/live2/<stream-key>.
 */
class YouTubeStreamer(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    bitRate: Int = (width * height * 5).coerceAtLeast(2_500_000),
    private val onStatus: (String) -> Unit,
) {
    private val client = RtmpClient(object : ConnectChecker {
        override fun onConnectionStarted(url: String) = onStatus("Connecting…")
        override fun onConnectionSuccess() = onStatus("LIVE")
        override fun onConnectionFailed(reason: String) = onStatus("Failed: $reason")
        override fun onNewBitrate(bitrate: Long) {}
        override fun onDisconnect() = onStatus("Stopped")
        override fun onAuthError() = onStatus("Auth error — check the stream key")
        override fun onAuthSuccess() {}
    })

    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    /** The surface the compositor draws the program frame into. */
    val inputSurface: Surface
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var streaming = false
    @Volatile private var sentConfig = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()   // must precede start()
        client.setOnlyVideo(true)                     // slice 1: video-only
        client.setVideoResolution(width, height)
        client.setFps(fps)
    }

    /** Start encoding and connect. [rtmpUrl] = rtmp://a.rtmp.youtube.com/live2/<key> */
    fun start(rtmpUrl: String) {
        encoder.start()
        streaming = true
        client.connect(rtmpUrl)
    }

    /** Pull encoded frames and send them. Runs on the compositor's GL thread. */
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

    /** Stop streaming and release the encoder. Call on the compositor GL thread (after detach). */
    fun stop() {
        streaming = false
        runCatching { client.disconnect() }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { inputSurface.release() }
        Log.i(TAG, "stream stopped")
    }

    val isStreaming: Boolean get() = streaming

    companion object { private const val TAG = "YouTubeStreamer" }
}
