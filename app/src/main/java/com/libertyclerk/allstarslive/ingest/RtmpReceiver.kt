package com.libertyclerk.allstarslive.ingest

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal RTMP server — the Mevo's "Custom RTMP" destination PUSHES H.264 to us.
 *
 * We accept one publisher, do the RTMP handshake, reassemble chunks, answer the
 * connect/createStream/publish commands, then pull H.264 out of the FLV video
 * messages: the AVCDecoderConfigurationRecord gives SPS/PPS ([onConfig]), and each
 * NALU message becomes an Annex-B access unit ([onVideo]) for MediaCodecVideoDecoder.
 * Video-only for now (audio is ignored). Heavily logged for live bring-up.
 */
class RtmpReceiver(
    private val port: Int = 1935,
    private val onConfig: (sps: ByteArray, pps: ByteArray) -> Unit,
    private val onVideo: (annexB: ByteArray, ptsMs: Long, keyframe: Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
    // Camera AAC audio (so the broadcast can use the camera's audio instead of the tablet mic).
    // [onAudioConfig] = AudioSpecificConfig (once); [onAudio] = each raw AAC frame. Default no-ops.
    private val onAudioConfig: (asc: ByteArray) -> Unit = {},
    private val onAudio: (aac: ByteArray, ptsMs: Long) -> Unit = { _, _ -> },
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var inChunkSize = 128

    fun start() {
        if (running) return
        running = true
        thread(name = "rtmp-server") {
            try {
                val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(port))
                server = s
                onStatus("Waiting for camera on port $port…")
                Log.i(TAG, "listening on :$port")
                while (running) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    onStatus("Camera connected — starting…")
                    Log.i(TAG, "publisher connected from ${c.inetAddress}")
                    runCatching { serve(c) }.onFailure { Log.e(TAG, "session ended", it) }
                    runCatching { c.close() }
                    if (running) onStatus("Waiting for camera on port $port…")
                }
            } catch (e: Exception) {
                if (running) { Log.e(TAG, "server error", e); onStatus("Receiver error: ${e.message}") }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    // ---- one publisher session ----
    private fun serve(c: Socket) {
        c.tcpNoDelay = true
        val counting = CountingInputStream(BufferedInputStream(c.getInputStream(), 1 shl 16))
        val inp = DataInputStream(counting)
        val out = DataOutputStream(c.getOutputStream())
        handshake(inp, out)
        Log.i(TAG, "handshake complete")
        inChunkSize = 128

        val streams = HashMap<Int, ChunkStream>()
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var configSent = false
        var frames = 0L
        var lastAck = 0L

        while (running) {
            val msg = readMessage(inp, streams) ?: break
            // RTMP flow control: acknowledge bytes received each window, or some
            // encoders stall/disconnect once their unacked window fills.
            if (counting.count - lastAck >= ACK_WINDOW) {
                sendControl(out, 3, be32Bytes((counting.count and 0xFFFFFFFFL).toInt()))   // Acknowledgement
                lastAck = counting.count
            }
            when (msg.type) {
                SET_CHUNK_SIZE -> { inChunkSize = be32(msg.data, 0); Log.i(TAG, "peer chunk size = $inChunkSize") }
                CMD_AMF0, CMD_AMF3 -> {
                    val off = if (msg.type == CMD_AMF3) 1 else 0   // AMF3 cmd is prefixed by a 0 byte
                    handleCommand(amfString(msg.data, off), msg.data, off, out)
                }
                MSG_VIDEO -> {
                    val d = msg.data
                    if (d.size < 2) continue
                    val codecId = d[0].toInt() and 0x0F
                    if (codecId != 7) continue            // 7 = AVC/H.264
                    val keyframe = ((d[0].toInt() shr 4) and 0x0F) == 1
                    when (d[1].toInt() and 0xFF) {
                        0 -> {                              // AVCDecoderConfigurationRecord
                            parseAvcc(d)?.let { (s, p) ->
                                sps = s; pps = p
                                onConfig(s, p); configSent = true
                                Log.i(TAG, "got SPS(${s.size})/PPS(${p.size}) from config record")
                            }
                        }
                        1 -> {                              // one or more length-prefixed NALUs
                            if (!configSent && sps != null && pps != null) { onConfig(sps!!, pps!!); configSent = true }
                            val au = naluToAnnexB(d, 5) ?: continue
                            onVideo(au, msg.timestamp.toLong(), keyframe)
                            if (keyframe || frames % 120 == 0L) Log.i(TAG, "video frames=$frames (${au.size}B, key=$keyframe)")
                            frames++
                        }
                    }
                }
                MSG_AUDIO -> {
                    val d = msg.data
                    if (d.size < 2) continue
                    if (((d[0].toInt() shr 4) and 0x0F) != 10) continue   // 10 = AAC (we only pass AAC through)
                    when (d[1].toInt() and 0xFF) {
                        0 -> onAudioConfig(d.copyOfRange(2, d.size))        // AudioSpecificConfig
                        1 -> onAudio(d.copyOfRange(2, d.size), msg.timestamp.toLong())   // raw AAC frame
                    }
                }
            }
        }
        Log.i(TAG, "session loop ended; frames=$frames")
    }

    // ---- RTMP handshake (simple/diffie-less; works with standard encoders) ----
    private fun handshake(inp: DataInputStream, out: DataOutputStream) {
        val c0 = inp.readUnsignedByte()                 // version
        val c1 = ByteArray(1536); inp.readFully(c1)     // time(4)+zero(4)+random(1528)
        out.writeByte(c0.coerceAtMost(3))               // S0
        val s1 = ByteArray(1536)                        // S1 (zeros + our "random")
        for (i in 8 until 1536) s1[i] = (i and 0xFF).toByte()
        out.write(s1)
        out.write(c1)                                   // S2 = echo C1
        out.flush()
        val c2 = ByteArray(1536); inp.readFully(c2)     // C2 (echo of our S1) — ignored
    }

    // ---- chunk reassembly ----
    private class ChunkStream {
        var timestamp = 0; var length = 0; var type = 0; var streamId = 0
        var buf: ByteArray? = null; var have = 0
    }
    private class Message(val type: Int, val timestamp: Int, val streamId: Int, val data: ByteArray)

    private fun readMessage(inp: DataInputStream, streams: HashMap<Int, ChunkStream>): Message? {
        while (true) {
            val b0 = inp.read(); if (b0 < 0) return null
            val fmt = (b0 shr 6) and 0x3
            var csid = b0 and 0x3F
            if (csid == 0) csid = 64 + inp.readUnsignedByte()
            else if (csid == 1) { val lo = inp.readUnsignedByte(); val hi = inp.readUnsignedByte(); csid = 64 + lo + hi * 256 }
            val cs = streams.getOrPut(csid) { ChunkStream() }

            var tsField = cs.timestamp
            if (fmt <= 2) tsField = readU24(inp)
            if (fmt <= 1) { cs.length = readU24(inp); cs.type = inp.readUnsignedByte() }
            if (fmt == 0) { cs.streamId = readLe32(inp) }
            if (tsField == 0xFFFFFF) tsField = inp.readInt()   // extended timestamp
            if (fmt == 0) cs.timestamp = tsField else if (fmt <= 2) cs.timestamp += tsField

            if (DEBUG_HDR) Log.i(TAG, "hdr fmt=$fmt cs=$csid t=${cs.type} len=${cs.length} have=${cs.have}")
            // Desync guard: a bogus length means we've lost chunk framing. Throw a clean
            // IOException (caught by the session) so the publisher reconnects, rather
            // than attempting a multi-MB allocation (OOM) or reading out of bounds.
            if (cs.length < 0 || cs.length > MAX_MSG) {
                throw java.io.IOException("bogus chunk length ${cs.length} (fmt=$fmt cs=$csid type=${cs.type})")
            }
            if (cs.buf == null) { cs.buf = ByteArray(cs.length); cs.have = 0 }
            val remaining = cs.length - cs.have
            val n = minOf(remaining, inChunkSize)
            inp.readFully(cs.buf!!, cs.have, n)
            cs.have += n

            if (cs.have >= cs.length) {
                val data = cs.buf!!; cs.buf = null
                return Message(cs.type, cs.timestamp, cs.streamId, data)
            }
            // else: more chunks for this message follow (with their own basic headers)
        }
    }

    // ---- command handling (AMF0) ----
    private fun handleCommand(name: String, data: ByteArray, off: Int, out: DataOutputStream) {
        Log.i(TAG, "command: $name")
        val txnId = amfNumberAfter(data, off)            // transaction id follows the name
        when (name) {
            "connect" -> {
                sendWindowAckSize(out, 5_000_000)
                sendSetPeerBandwidth(out, 5_000_000)
                sendSetChunkSize(out, 4096)
                sendConnectResult(out, txnId)
            }
            "releaseStream", "FCPublish", "FCUnpublish", "deleteStream" -> { /* ack not required */ }
            "createStream" -> sendCreateStreamResult(out, txnId, 1.0)
            "publish" -> { sendPublishStart(out); onStatus("Receiving video from camera") }
        }
    }

    // ---- AMF0 / control message writers ----
    private fun sendControl(out: DataOutputStream, type: Int, payload: ByteArray, csid: Int = 2, streamId: Int = 0) {
        // fmt 0 chunk, fits in one chunk (control/command payloads are small)
        out.writeByte((0 shl 6) or csid)
        out.write(byteArrayOf(0, 0, 0))                  // timestamp
        out.write(u24(payload.size))                     // length
        out.writeByte(type)
        out.write(le32(streamId))
        out.write(payload)
        out.flush()
    }
    private fun sendWindowAckSize(out: DataOutputStream, n: Int) = sendControl(out, 5, be32Bytes(n))
    private fun sendSetPeerBandwidth(out: DataOutputStream, n: Int) = sendControl(out, 6, be32Bytes(n) + byteArrayOf(2))
    private fun sendSetChunkSize(out: DataOutputStream, n: Int) = sendControl(out, 1, be32Bytes(n))

    private fun sendConnectResult(out: DataOutputStream, txn: Double) {
        val b = Amf()
        b.str("_result"); b.num(txn)
        b.objStart(); b.prop("fmsVer", "FMS/3,0,1,123"); b.prop("capabilities", 31.0); b.objEnd()
        b.objStart(); b.prop("level", "status"); b.prop("code", "NetConnection.Connect.Success")
        b.prop("description", "Connection succeeded."); b.objEnd()
        sendControl(out, CMD_AMF0, b.bytes(), csid = 3)
    }
    private fun sendCreateStreamResult(out: DataOutputStream, txn: Double, streamId: Double) {
        val b = Amf(); b.str("_result"); b.num(txn); b.nullVal(); b.num(streamId)
        sendControl(out, CMD_AMF0, b.bytes(), csid = 3)
    }
    private fun sendPublishStart(out: DataOutputStream) {
        val b = Amf(); b.str("onStatus"); b.num(0.0); b.nullVal()
        b.objStart(); b.prop("level", "status"); b.prop("code", "NetStream.Publish.Start")
        b.prop("description", "Start publishing"); b.objEnd()
        sendControl(out, CMD_AMF0, b.bytes(), csid = 3, streamId = 1)
    }

    // ---- H.264 extraction ----
    private fun parseAvcc(d: ByteArray): Pair<ByteArray, ByteArray>? {
        // FLV video: [0]=frame/codec, [1]=avcType(0), [2..4]=cts, then AVCDecoderConfigurationRecord
        var p = 5
        if (d.size < p + 7) return null
        p += 5                                            // version, profile, compat, level, lengthSizeMinusOne
        val numSps = d[p].toInt() and 0x1F; p += 1
        var sps: ByteArray? = null
        repeat(numSps) {
            val len = be16(d, p); p += 2
            if (sps == null) sps = annexB(d, p, len)
            p += len
        }
        if (p + 1 > d.size) return null
        val numPps = d[p].toInt() and 0xFF; p += 1
        var pps: ByteArray? = null
        repeat(numPps) {
            val len = be16(d, p); p += 2
            if (pps == null) pps = annexB(d, p, len)
            p += len
        }
        return if (sps != null && pps != null) sps!! to pps!! else null
    }
    /** Length-prefixed (4-byte) NALUs → Annex-B (00 00 00 01 start codes). */
    private fun naluToAnnexB(d: ByteArray, start: Int): ByteArray? {
        var p = start
        val out = ArrayList<Byte>(d.size + 16)
        while (p + 4 <= d.size) {
            val len = be32(d, p); p += 4
            if (len <= 0 || p + len > d.size) break
            out.add(0); out.add(0); out.add(0); out.add(1)
            for (i in 0 until len) out.add(d[p + i])
            p += len
        }
        return if (out.isEmpty()) null else out.toByteArray()
    }
    private fun annexB(d: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArray(len + 4); out[3] = 1; System.arraycopy(d, off, out, 4, len); return out
    }

    // ---- little binary helpers ----
    private fun readU24(inp: DataInputStream): Int { val a=inp.readUnsignedByte();val b=inp.readUnsignedByte();val c=inp.readUnsignedByte(); return (a shl 16) or (b shl 8) or c }
    private fun readLe32(inp: DataInputStream): Int { val a=inp.readUnsignedByte();val b=inp.readUnsignedByte();val c=inp.readUnsignedByte();val e=inp.readUnsignedByte(); return a or (b shl 8) or (c shl 16) or (e shl 24) }
    private fun be16(d: ByteArray, p: Int) = ((d[p].toInt() and 0xFF) shl 8) or (d[p+1].toInt() and 0xFF)
    private fun be32(d: ByteArray, p: Int) = ((d[p].toInt() and 0xFF) shl 24) or ((d[p+1].toInt() and 0xFF) shl 16) or ((d[p+2].toInt() and 0xFF) shl 8) or (d[p+3].toInt() and 0xFF)
    private fun u24(n: Int) = byteArrayOf((n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
    private fun be32Bytes(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun amfString(d: ByteArray, off: Int): String { if (off >= d.size || d[off].toInt() != 2) return ""; val len = be16(d, off+1); return String(d, off+3, len) }
    private fun amfNumberAfter(d: ByteArray, off: Int): Double {
        // skip the leading AMF string (marker 2 + u16 len + bytes), then read AMF number (marker 0 + 8 bytes BE double)
        if (off >= d.size || d[off].toInt() != 2) return 0.0
        var p = off + 3 + be16(d, off + 1)
        if (p >= d.size || d[p].toInt() != 0) return 0.0
        p += 1
        var bits = 0L; for (i in 0 until 8) bits = (bits shl 8) or (d[p+i].toLong() and 0xFF)
        return Double.fromBits(bits)
    }

    /** Tiny AMF0 writer for our few responses. */
    private class Amf {
        private val o = ArrayList<Byte>(64)
        private fun u16(n: Int) { o.add((n shr 8).toByte()); o.add(n.toByte()) }
        fun str(s: String) { o.add(2); u16(s.length); s.forEach { o.add(it.code.toByte()) } }
        fun num(n: Double) { o.add(0); val b = java.lang.Double.doubleToLongBits(n); for (i in 7 downTo 0) o.add((b shr (i*8)).toByte()) }
        fun nullVal() { o.add(5) }
        fun objStart() { o.add(3) }                       // AMF0 object marker
        fun prop(k: String, v: String) { u16(k.length); k.forEach { o.add(it.code.toByte()) }; str(v) }
        fun prop(k: String, v: Double) { u16(k.length); k.forEach { o.add(it.code.toByte()) }; num(v) }
        fun objEnd() { o.add(0); o.add(0); o.add(9) }     // empty key + object-end marker
        fun bytes(): ByteArray = o.toByteArray()
    }

    /** Counts every byte read so we can send RTMP Acknowledgements. */
    private class CountingInputStream(inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L; private set
        override fun read(): Int { val b = `in`.read(); if (b >= 0) count++; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = `in`.read(b, off, len); if (n > 0) count += n; return n
        }
    }

    companion object {
        private const val TAG = "RtmpReceiver"
        private const val DEBUG_HDR = false  // flip to true to log every chunk header
        private const val MAX_MSG = 8 * 1024 * 1024   // sanity cap; real frames are < ~300KB
        private const val ACK_WINDOW = 2_500_000L     // acknowledge received bytes this often
        private const val SET_CHUNK_SIZE = 1
        private const val MSG_AUDIO = 8
        private const val MSG_VIDEO = 9
        private const val CMD_AMF3 = 17
        private const val CMD_AMF0 = 20
    }
}
