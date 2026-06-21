package com.libertyclerk.allstarslive.ingest

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * M1 ingest spike UI.
 *
 * Full-screen [SurfaceView] for the live picture with a translucent control bar
 * (SRT URL + Connect/Disconnect) and a live FPS/latency HUD. The [VideoSource]
 * is injected, so this same screen drives the placeholder today and the real
 * SRT transport once it's chosen — "see a frame" is the whole test.
 */
@Composable
fun SrtIngestScreen() {
    val ctx = LocalContext.current
    val source = remember { SrtVideoSource(ctx) }
    val stats by source.stats.collectAsStateWithLifecycle()

    // Mevo is the SRT listener at its own IP:port (from the Mevo app's SRT screen);
    // we connect as the caller. Editable at runtime since the IP can change per network.
    var url by remember { mutableStateOf("srt://192.168.17.1:4201") }
    var connected by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }

    DisposableEffect(Unit) {
        onDispose { source.stop() }
    }

    // Render the video at its true aspect ratio (letterboxed, centered) so the
    // 16:9 camera isn't stretched to the ~16:10 screen.
    val videoAspect = if (stats.widthPx > 0 && stats.heightPx > 0)
        stats.widthPx.toFloat() / stats.heightPx else 16f / 9f

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {

        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(videoAspect),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surface = holder.surface
                            if (connected) source.start(url, holder.surface)
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surface = null
                            source.stop()
                        }
                    })
                }
            },
        )

        Hud(stats, Modifier.align(Alignment.TopStart).padding(12.dp))

        // Control bar.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("SRT source URL") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                modifier = Modifier.width(150.dp),
                onClick = {
                    if (connected) {
                        source.stop()
                        connected = false
                    } else {
                        surface?.let { source.start(url, it) }
                        connected = true
                    }
                },
            ) {
                Text(if (connected) "Disconnect" else "Connect")
            }
        }
    }
}

@Composable
private fun Hud(stats: VideoStats, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color(0x99000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        HudLine("STATE", stats.state.name, stateColor(stats.state))
        HudLine("FPS", String.format("%.1f", stats.fps))
        HudLine("LATENCY", "${stats.latencyMs} ms")
        HudLine("SIZE", if (stats.widthPx > 0) "${stats.widthPx}×${stats.heightPx}" else "—")
        HudLine("FRAMES", stats.framesRendered.toString())
        if (stats.message.isNotEmpty()) {
            Text(stats.message, color = Color(0xFFFFC107), fontSize = 11.sp)
        }
    }
}

@Composable
private fun HudLine(label: String, value: String, valueColor: Color = Color.White) {
    Row {
        Text(
            "$label ",
            color = Color(0xFF9AA0A6),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(value, color = valueColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun stateColor(state: IngestState): Color = when (state) {
    IngestState.PLAYING -> Color(0xFF4CAF50)
    IngestState.ERROR -> Color(0xFFE53935)
    IngestState.RECONNECTING, IngestState.BUFFERING, IngestState.CONNECTING -> Color(0xFFFFC107)
    IngestState.IDLE -> Color(0xFF9AA0A6)
}
