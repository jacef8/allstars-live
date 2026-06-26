package com.libertyclerk.allstarslive.stream

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local fallback for when a stable connection isn't possible: records the SAME composited
 * program frame (camera + scorebug) the streamer would push — plus mic audio — to an MP4 on
 * the tablet, via [MediaMuxer]. Reuses the compositor's encoder-surface hook exactly like
 * [YouTubeStreamer]; the only difference is the encoded samples go to a file, not RTMP.
 *
 * Saved to the device's Movies/All-Stars Live folder (visible in Gallery/Files) so it can be
 * uploaded to YouTube later. Record-only: it shares the single encoder surface with streaming,
 * so the two don't run at once.
 */
class LocalRecorder(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    bitRate: Int = (width * height * 5).coerceAtLeast(2_500_000),
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    /** The surface the compositor draws the program frame into. */
    val inputSurface: Surface
    private val bufferInfo = MediaCodec.BufferInfo()

    private val sampleRate = 44100
    private val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val audioInfo = MediaCodec.BufferInfo()
    private var audioThread: Thread? = null
    @Volatile private var hasAudio = false

    // ---- muxer (guarded: video drains on the GL thread, audio on its own thread) ----
    private val muxLock = Any()
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var outFile: File? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    @Volatile private var recording = false

    /** Human-readable place the file landed, for a "Saved to …" message. */
    var savedLocation: String = ""; private set

    init {
        val vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        encoder.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()

        val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        audioEncoder.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        openOutput()
    }

    /** Create the MP4 sink: MediaStore (API 29+, shows in Gallery) or app Movies dir (older). */
    private fun openOutput() {
        val name = "AllStars_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/All-Stars Live")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create the recording file")
            pfd = resolver.openFileDescriptor(outUri!!, "rw")
                ?: throw IllegalStateException("Couldn't open the recording file")
            muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            savedLocation = "Movies/All-Stars Live/$name"
        } else {
            val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "All-Stars Live").apply { mkdirs() }
            val file = File(dir, name)
            outFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            savedLocation = file.absolutePath
        }
    }

    fun start() {
        encoder.start()
        audioEncoder.start()
        recording = true
        startAudio()
        onStatus("Recording")
        Log.i(TAG, "recording → $savedLocation")
    }

    /** Add a track once its format is known; start the muxer when every expected track is in. */
    private fun maybeStartMuxer() {
        // synchronized by caller (muxLock)
        if (muxerStarted) return
        if (videoTrack >= 0 && (!hasAudio || audioTrack >= 0)) {
            muxer?.start()
            muxerStarted = true
            // First few frames may have been skipped while waiting for both formats — ask for a
            // fresh keyframe so playback starts cleanly.
            runCatching {
                encoder.setParameters(android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }
    }

    /** Pull encoded VIDEO frames and write them. Runs on the compositor's GL thread. */
    fun drain() {
        if (!recording) return
        while (true) {
            val i = try { encoder.dequeueOutputBuffer(bufferInfo, 0) } catch (e: IllegalStateException) { return }
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                    if (videoTrack < 0) videoTrack = muxer?.addTrack(encoder.outputFormat) ?: -1
                    maybeStartMuxer()
                }
                i >= 0 -> {
                    val buf = encoder.getOutputBuffer(i)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && bufferInfo.size > 0 && !isConfig) synchronized(muxLock) {
                        if (muxerStarted && videoTrack >= 0) {
                            runCatching { muxer?.writeSampleData(videoTrack, buf, bufferInfo) }
                        }
                    }
                    encoder.releaseOutputBuffer(i, false)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        audioThread = Thread {
            val chunkSamples = 1024
            val chunkBytes = chunkSamples * 2
            val pcm = ByteArray(chunkBytes)
            val usPerChunk = chunkSamples * 1_000_000L / sampleRate
            var ptsUs = 0L
            val mic = runCatching {
                val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, chunkBytes * 4),
                ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
            if (mic == null) {
                // No mic/permission → record video only (a silent MP4 is fine for upload later).
                hasAudio = false
                synchronized(muxLock) { maybeStartMuxer() }
                Log.i(TAG, "recording audio: none (video only)")
                return@Thread
            }
            hasAudio = true
            mic.runCatching { startRecording() }
            try {
                while (recording) {
                    val n = mic.read(pcm, 0, chunkBytes)
                    if (n <= 0) { Thread.sleep(5); continue }
                    feedAudio(pcm, n, ptsUs)
                    ptsUs += usPerChunk
                    drainAudio()
                }
            } catch (_: InterruptedException) {
            } finally {
                mic.runCatching { stop() }
                mic.runCatching { release() }
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
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                    if (audioTrack < 0) audioTrack = muxer?.addTrack(audioEncoder.outputFormat) ?: -1
                    maybeStartMuxer()
                }
                i >= 0 -> {
                    val buf = audioEncoder.getOutputBuffer(i)
                    val isConfig = audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && audioInfo.size > 0 && !isConfig) synchronized(muxLock) {
                        if (muxerStarted && audioTrack >= 0) {
                            runCatching { muxer?.writeSampleData(audioTrack, buf, audioInfo) }
                        }
                    }
                    audioEncoder.releaseOutputBuffer(i, false)
                }
            }
        }
    }

    /** Stop, finalize the MP4, and publish it. Call on the compositor GL thread (after detach). */
    fun stop() {
        recording = false
        audioThread?.interrupt(); audioThread = null
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { audioEncoder.stop() }
        runCatching { audioEncoder.release() }
        synchronized(muxLock) {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
        }
        runCatching { pfd?.close() }
        runCatching { inputSurface.release() }
        // Publish the MediaStore entry so it appears in Gallery/Files.
        if (outUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                appContext.contentResolver.update(outUri!!, values, null, null)
            }
        }
        onStatus("Saved")
        Log.i(TAG, "recording saved → $savedLocation")
    }

    val isRecording: Boolean get() = recording

    companion object { private const val TAG = "LocalRecorder" }
}
