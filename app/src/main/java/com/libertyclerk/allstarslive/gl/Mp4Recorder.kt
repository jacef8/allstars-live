package com.libertyclerk.allstarslive.gl

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface

/**
 * Records the composited program frame to a local .mp4.
 *
 *   [VideoCompositor] draws each frame into [inputSurface] (the encoder's input
 *   surface) -> MediaCodec H.264 encoder -> MediaMuxer -> file.
 *
 * The compositor calls [drain] after every frame it swaps to the encoder surface
 * (so encoding keeps pace), and [finish] on stop to flush the tail and close the
 * file. All MediaCodec/muxer calls happen on the compositor's GL thread, which is
 * the single thread that owns the encoder surface — keep it that way.
 */
class Mp4Recorder(
    private val outputPath: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    bitRate: Int = (width * height * 5).coerceAtLeast(2_000_000),  // ~5 Mbps at 1080p, scaled
) {
    private val encoder: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()

    /** The surface the compositor draws the program frame into. */
    val inputSurface: Surface

    private var trackIndex = -1
    private var muxerStarted = false
    @Volatile private var released = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()   // must be before start()
        encoder.start()

        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        Log.i(TAG, "recording -> $outputPath (${width}x$height @ ${fps}fps, ${bitRate / 1000}kbps)")
    }

    /**
     * Pull encoded output and write it to the muxer. Call with [endOfStream]=true
     * once at the end to flush; otherwise it returns as soon as no output is ready.
     */
    fun drain(endOfStream: Boolean) {
        if (released) return
        if (endOfStream) runCatching { encoder.signalEndOfInputStream() }
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return   // no data this tick; let frames keep coming
                    // else keep looping until EOS arrives
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "format changed twice" }
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val buf = encoder.getOutputBuffer(outIndex)
                    if (buf != null) {
                        // Drop codec-config bytes (already folded into the track format).
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                        if (bufferInfo.size > 0 && muxerStarted) {
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, buf, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Flush the tail and close the file + encoder. Safe to call once. */
    fun finish() {
        if (released) return
        runCatching { drain(true) }
        released = true
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        if (muxerStarted) {
            runCatching { muxer.stop() }
        }
        runCatching { muxer.release() }
        runCatching { inputSurface.release() }
        Log.i(TAG, "recording finished")
    }

    companion object {
        private const val TAG = "Mp4Recorder"
    }
}
