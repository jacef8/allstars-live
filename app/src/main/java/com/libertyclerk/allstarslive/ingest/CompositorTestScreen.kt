package com.libertyclerk.allstarslive.ingest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.libertyclerk.allstarslive.gl.Mp4Recorder
import com.libertyclerk.allstarslive.gl.VideoCompositor
import com.libertyclerk.allstarslive.stream.YouTubeStreamer
import com.libertyclerk.allstarslive.youtube.YouTubeAuth
import com.libertyclerk.allstarslive.youtube.YouTubeLive
import java.io.File

private const val PROG_W = 1280
private const val PROG_H = 720

/** Mutable refs shared between the Surface callbacks and the Record / Go Live buttons. */
private class M2State {
    var comp: VideoCompositor? = null
    var rec: Mp4Recorder? = null
    var streamer: YouTubeStreamer? = null
}

/**
 * M2 on-device test. Runs the [StubVideoSource] test pattern through the real GL
 * [VideoCompositor], blends a sample scorebug overlay on top, shows it on screen,
 * and records the clean program frame to a local .mp4 — proving the whole
 * composite + record pipeline without needing the camera live.
 */
@Composable
fun CompositorTestScreen() {
    val ctx = LocalContext.current
    val stub = remember { StubVideoSource() }
    val st = remember { M2State() }
    val overlay = remember { buildScorebugOverlay(PROG_W, PROG_H, "AWAY", "HOME", 3, 2, 4, true, 1, 2, 1) }
    val stats by stub.stats.collectAsState()

    var recording by remember { mutableStateOf(false) }
    var lastPath by remember { mutableStateOf<String?>(null) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var streaming by remember { mutableStateOf(false) }
    var streamKey by remember { mutableStateOf("") }
    var streamStatus by remember { mutableStateOf("") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Start pushing the composited frame to YouTube at rtmp://…/live2/<key>. Main thread.
    fun goLiveWithKey(key: String) {
        val comp = st.comp ?: return
        val s = YouTubeStreamer(PROG_W, PROG_H, onStatus = { status ->
            streamStatus = status
            if (streaming && (status.startsWith("Failed") || status.startsWith("Auth"))) {
                streaming = false
                val old = st.streamer; st.streamer = null
                st.comp?.detachEncoder { old?.stop() }
            }
        })
        comp.setEncoderSurface(s.inputSurface, PROG_W, PROG_H) { s.drain() }
        s.start("rtmp://a.rtmp.youtube.com/live2/$key")
        st.streamer = s
        streaming = true
    }

    DisposableEffect(Unit) {
        onDispose {
            stub.stop()
            st.rec?.let { r -> st.comp?.detachEncoder { r.finish() }; st.rec = null }
            st.streamer?.let { s -> st.comp?.detachEncoder { s.stop() }; st.streamer = null }
            st.comp?.release()
            st.comp = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {

        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val comp = VideoCompositor()
                            comp.start(holder.surface)
                            comp.setInputSize(PROG_W, PROG_H)
                            comp.setVideoSize(PROG_W, PROG_H)
                            comp.setOverlay(overlay)
                            stub.start("", comp.inputSurface)
                            st.comp = comp
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            stub.stop()
                            val comp = st.comp
                            st.rec?.let { r -> comp?.detachEncoder { r.finish() }; st.rec = null }
                            st.streamer?.let { s -> comp?.detachEncoder { s.stop() }; st.streamer = null }
                            comp?.release()
                            st.comp = null
                        }
                    })
                }
            },
        )

        // HUD
        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp)
                .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("M2 COMPOSITOR TEST", color = Color(0xFFA3E635), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("state ${stats.state}", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text("fps ${"%.1f".format(stats.fps)}   frames ${stats.framesRendered}", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            if (recording) Text("● REC", color = Color(0xFFFF3B5C), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }

        // Controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            savedPath?.let { Text("Saved: $it", color = Color(0xFF9AA0A6), fontSize = 12.sp) }
            Button(
                onClick = {
                    val comp = st.comp ?: return@Button
                    if (!recording) {
                        val dir = File(ctx.getExternalFilesDir(null), "recordings").apply { mkdirs() }
                        val f = File(dir, "clip-${System.currentTimeMillis()}.mp4")
                        val rec = Mp4Recorder(f.absolutePath, PROG_W, PROG_H)
                        comp.setEncoderSurface(rec.inputSurface, PROG_W, PROG_H) { rec.drain(false) }
                        st.rec = rec
                        lastPath = f.absolutePath
                        savedPath = null
                        recording = true
                    } else {
                        val rec = st.rec
                        recording = false
                        comp.detachEncoder { rec?.finish() }
                        st.rec = null
                        savedPath = lastPath
                    }
                },
                enabled = !streaming,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (recording) Color(0xFFFF3B5C) else Color(0xFFA3E635),
                    contentColor = Color(0xFF0B0E13),
                ),
            ) {
                Text(if (recording) "Stop recording" else "Record .mp4", fontSize = 16.sp)
            }

            // --- Go Live to YouTube (M3) ---
            if (streamStatus.isNotEmpty()) Text("Stream: $streamStatus", color = Color(0xFF4C9AFF), fontSize = 12.sp)
            OutlinedTextField(
                value = streamKey,
                onValueChange = { streamKey = it },
                label = { Text("Stream key (optional — blank uses your YouTube)") },
                singleLine = true,
                enabled = !streaming,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            Button(
                onClick = {
                    if (streaming) {
                        val s = st.streamer
                        streaming = false
                        st.comp?.detachEncoder { s?.stop() }
                        st.streamer = null
                    } else {
                        val key = streamKey.trim()
                        if (key.isNotBlank()) {
                            goLiveWithKey(key)                       // manual override
                        } else {
                            // One-tap: use the connected YouTube account to set up the broadcast.
                            streamStatus = "Setting up your YouTube broadcast…"
                            YouTubeAuth.client(ctx).authorize(YouTubeAuth.request())
                                .addOnSuccessListener { result ->
                                    val token = result.accessToken
                                    if (token == null) {
                                        streamStatus = "Connect YouTube in Settings first"
                                        return@addOnSuccessListener
                                    }
                                    Thread {
                                        runCatching { YouTubeLive.startBroadcast(token, "All-Stars Live") }
                                            .onSuccess { live -> mainHandler.post { streamStatus = "Starting…"; goLiveWithKey(live.streamKey) } }
                                            .onFailure { e -> mainHandler.post { streamStatus = "YouTube setup failed: ${e.message}" } }
                                    }.start()
                                }
                                .addOnFailureListener { streamStatus = "YouTube auth failed: ${it.message}" }
                        }
                    }
                },
                enabled = !recording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (streaming) Color(0xFFFF3B5C) else Color(0xFF4C9AFF),
                    contentColor = Color(0xFFFFFFFF),
                ),
            ) {
                Text(if (streaming) "Stop streaming" else "Go Live", fontSize = 16.sp)
            }
        }
    }
}

/**
 * Draws a broadcast-style scorebug onto a transparent, program-sized bitmap
 * (premultiplied alpha — what [VideoCompositor]/[GlScene] blend on top of the video).
 */
fun buildScorebugOverlay(
    w: Int, h: Int,
    away: String, home: String,
    awayScore: Int, homeScore: Int,
    inning: Int, topHalf: Boolean,
    balls: Int, strikes: Int, outs: Int,
): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)   // transparent
    val c = Canvas(bmp)
    val pad = w * 0.025f
    val boxW = w * 0.44f
    val boxH = h * 0.18f
    val x = pad
    val y = h - boxH - pad
    val rad = boxH * 0.12f
    val rowH = boxH / 2f
    val divX = x + boxW * 0.66f
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }

    // background + lime keyline
    p.color = 0xF00E1626.toInt()
    c.drawRoundRect(RectF(x, y, x + boxW, y + boxH), rad, rad, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = h * 0.004f
    p.color = 0xFFA3E635.toInt()
    c.drawRoundRect(RectF(x, y, x + boxW, y + boxH), rad, rad, p)
    p.style = Paint.Style.FILL

    fun row(ry: Float, abbr: String, score: Int, active: Boolean, chip: Int) {
        p.color = chip
        c.drawRect(x + boxW * 0.04f, ry + rowH * 0.24f, x + boxW * 0.06f, ry + rowH * 0.76f, p)
        p.color = if (active) 0xFFFFFFFF.toInt() else 0xFF8C97A8.toInt()
        p.textAlign = Paint.Align.LEFT
        p.textSize = rowH * 0.46f
        c.drawText(abbr, x + boxW * 0.10f, ry + rowH * 0.66f, p)
        p.textAlign = Paint.Align.RIGHT
        p.textSize = rowH * 0.62f
        c.drawText(score.toString(), divX - boxW * 0.03f, ry + rowH * 0.70f, p)
    }
    row(y, away, awayScore, topHalf, 0xFF2E6BE6.toInt())
    row(y + rowH, home, homeScore, !topHalf, 0xFFE2574C.toInt())

    // divider + inning/count on the right
    p.color = 0xFF1E2A44.toInt()
    c.drawRect(divX, y + boxH * 0.12f, divX + h * 0.002f, y + boxH * 0.88f, p)
    val rcx = (divX + x + boxW) / 2f
    p.textAlign = Paint.Align.CENTER
    p.color = 0xFFE0E3E8.toInt()
    p.textSize = boxH * 0.22f
    c.drawText((if (topHalf) "T" else "B") + inning, rcx, y + boxH * 0.36f, p)
    p.color = 0xFFA3E635.toInt()
    p.textSize = boxH * 0.18f
    c.drawText("B$balls S$strikes O$outs", rcx, y + boxH * 0.76f, p)
    return bmp
}
