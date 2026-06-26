package com.libertyclerk.allstarslive.ingest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
fun CompositorTestScreen(onUseCamera: () -> Unit = {}) {
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
    var eventTitle by remember { mutableStateOf("All-Stars Live") }
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
        comp.setEncoderSurface(s.inputSurface, PROG_W, PROG_H, s.avBaseNs) { s.drain() }   // shared a/v clock → lip-sync
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
            Text("TEST PATTERN", color = Color(0xFFA3E635), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("← Use camera", color = Color(0xFF4C9AFF), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onUseCamera() })
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
                value = eventTitle,
                onValueChange = { eventTitle = it },
                label = { Text("Broadcast name (shown on YouTube)") },
                singleLine = true,
                enabled = !streaming,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
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
                                        runCatching { YouTubeLive.startBroadcast(token, eventTitle.ifBlank { "All-Stars Live" }) }
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
    awayLogo: Bitmap? = null, homeLogo: Bitmap? = null,
): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)   // transparent
    val c = Canvas(bmp)
    val pad = w * 0.022f
    val boxW = w * 0.47f
    val boxH = h * 0.205f
    val x = pad
    val y = h - boxH - pad
    val rad = boxH * 0.14f
    val rowH = boxH / 2f
    val accentW = boxW * 0.013f                 // brand-red accent down the left edge
    val countW = boxW * 0.31f                    // right-hand count panel (widened so inning/count don't crowd)
    val divX = x + boxW - countW
    val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    val red = 0xFFE11D2E.toInt()
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- panel: soft shadow, deep-navy body, hairline, red left accent ----
    p.color = 0x60000000
    c.drawRoundRect(RectF(x + 2f, y + 5f, x + boxW + 2f, y + boxH + 5f), rad, rad, p)
    p.color = 0xF20B1322.toInt()
    c.drawRoundRect(RectF(x, y, x + boxW, y + boxH), rad, rad, p)
    // red accent clipped to the rounded left edge
    c.save(); c.clipRect(x, y, x + accentW * 2.6f, y + boxH)
    p.color = red; c.drawRoundRect(RectF(x, y, x + boxW, y + boxH), rad, rad, p); c.restore()
    p.style = Paint.Style.STROKE; p.strokeWidth = h * 0.0022f; p.color = 0x33FFFFFF
    c.drawRoundRect(RectF(x, y, x + boxW, y + boxH), rad, rad, p)
    p.style = Paint.Style.FILL

    val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // FIXED emblem slot + name X — identical for BOTH rows whether or not a team has a logo, so the
    // names always line up, and there's a real gap so the logo never overlaps the name.
    val emblemX = x + boxW * 0.05f
    val emblemSz = rowH * 0.72f
    val nameX = emblemX + emblemSz + boxW * 0.06f
    fun row(ry: Float, abbr: String, score: Int, active: Boolean, chip: Int, logo: Bitmap?) {
        val cy = ry + rowH / 2f
        if (active) {                            // batting team: faint highlight + red tick
            p.color = 0x18FFFFFF
            c.drawRect(x + accentW * 2.6f, ry, divX, ry + rowH, p)
            p.color = red
            c.drawRect(x + accentW * 2.6f, ry + rowH * 0.16f, x + accentW * 4.0f, ry + rowH * 0.84f, p)
        }
        if (logo != null) {                      // prominent logo on a white rounded tile, in the slot
            val ly = cy - emblemSz / 2f
            p.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(RectF(emblemX - emblemSz * 0.08f, ly - emblemSz * 0.08f, emblemX + emblemSz * 1.08f, ly + emblemSz * 1.08f), emblemSz * 0.22f, emblemSz * 0.22f, p)
            c.drawBitmap(logo, null, RectF(emblemX, ly, emblemX + emblemSz, ly + emblemSz), logoPaint)
        } else {                                 // no logo: slim team-color chip centered in the same slot
            val chW = boxW * 0.018f
            p.color = chip
            c.drawRoundRect(RectF(emblemX + emblemSz / 2f - chW / 2f, cy - rowH * 0.30f, emblemX + emblemSz / 2f + chW / 2f, cy + rowH * 0.30f), chW * 0.5f, chW * 0.5f, p)
        }
        p.typeface = sans
        p.color = if (active) 0xFFFFFFFF.toInt() else 0xFF8A97AD.toInt()
        p.textAlign = Paint.Align.LEFT
        p.textSize = rowH * 0.40f
        c.drawText(abbr.uppercase().take(5), nameX, cy + rowH * 0.14f, p)
        p.typeface = mono
        p.color = if (active) 0xFFFFFFFF.toInt() else 0xFFAEB8C8.toInt()
        p.textAlign = Paint.Align.RIGHT
        p.textSize = rowH * 0.64f
        c.drawText(score.toString(), divX - boxW * 0.045f, cy + rowH * 0.23f, p)
    }
    row(y, away, awayScore, topHalf, 0xFF2E6BE6.toInt(), awayLogo)
    p.color = 0x22FFFFFF                          // thin divider between the two rows
    c.drawRect(x + accentW * 2.6f, y + rowH - h * 0.0011f, divX, y + rowH + h * 0.0011f, p)
    row(y + rowH, home, homeScore, !topHalf, red, homeLogo)

    // ---- count panel (right) ----
    p.color = 0x33FFFFFF
    c.drawRect(divX, y + boxH * 0.12f, divX + h * 0.0016f, y + boxH * 0.88f, p)
    val rcx = divX + countW / 2f
    // inning: a small up/down triangle + the number, CENTERED as a group (so single- or double-digit
    // innings stay centered and never crowd the divider/edge).
    val tri = boxH * 0.095f
    val innMidY = y + boxH * 0.27f
    p.typeface = mono; p.color = 0xFFFFFFFF.toInt(); p.textSize = boxH * 0.26f
    val innStr = inning.toString()
    p.textAlign = Paint.Align.LEFT
    val innW = p.measureText(innStr)
    val gap = countW * 0.06f
    val gx = rcx - (tri + gap + innW) / 2f          // left edge of the centered triangle+number group
    val path = Path()
    if (topHalf) { path.moveTo(gx, innMidY + tri / 2f); path.lineTo(gx + tri, innMidY + tri / 2f); path.lineTo(gx + tri / 2f, innMidY - tri / 2f) }
    else { path.moveTo(gx, innMidY - tri / 2f); path.lineTo(gx + tri, innMidY - tri / 2f); path.lineTo(gx + tri / 2f, innMidY + tri / 2f) }
    path.close()
    p.color = 0xFFF0B43E.toInt(); c.drawPath(path, p)
    p.color = 0xFFFFFFFF.toInt()
    c.drawText(innStr, gx + tri + gap, innMidY + boxH * 0.095f, p)
    // count: balls-strikes (centered, with the wider panel it now has clear margin)
    p.textAlign = Paint.Align.CENTER; p.color = 0xFFFFFFFF.toInt(); p.textSize = boxH * 0.23f
    c.drawText("$balls-$strikes", rcx, y + boxH * 0.64f, p)
    // outs: two dots (filled red for each out)
    val dotR = boxH * 0.045f
    val dotY = y + boxH * 0.82f
    for (i in 0..1) {
        p.color = if (i < outs) red else 0x44FFFFFF
        c.drawCircle(rcx - dotR * 1.9f + i * dotR * 3.8f, dotY, dotR, p)
    }
    return bmp
}
