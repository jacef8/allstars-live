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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    val settings = remember { CameraSettings(ctx) }
    val stats by source.stats.collectAsStateWithLifecycle()

    // Connection config — persisted & editable (not hardcoded) so the app isn't
    // tied to one camera and a Wi-Fi password change is an in-app edit. The SRT
    // URL is the camera's listener address; we connect as the caller.
    var url by remember { mutableStateOf(settings.url) }
    var ssid by remember { mutableStateOf(settings.wifiSsid) }
    var pass by remember { mutableStateOf(settings.wifiPassphrase) }
    var connected by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }

    // Push the camera Wi-Fi creds into the source so start() can join it for us.
    fun applyCreds() {
        source.wifiSsid = ssid
        source.wifiPassphrase = pass
    }

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
                            if (connected) {
                                applyCreds()
                                source.start(url, holder.surface)
                            }
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

        // Step-by-step connection guide — stays up until the feed is live, so the
        // operator always has the routine in front of them when nothing is showing.
        if (stats.state != IngestState.PLAYING) {
            SetupGuide(Modifier.align(Alignment.Center))
        }

        // Control bar. Wi-Fi name/password + SRT URL are editable and persisted;
        // on Connect the app joins that Wi-Fi itself. Fields hide once we're live.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text("Camera Wi-Fi name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Wi-Fi password") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("SRT source URL") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
            Button(
                modifier = Modifier.width(180.dp).align(Alignment.End),
                onClick = {
                    if (connected) {
                        source.stop()
                        connected = false
                    } else {
                        // Persist whatever the operator entered, then connect.
                        settings.url = url
                        settings.wifiSsid = ssid
                        settings.wifiPassphrase = pass
                        applyCreds()
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

/**
 * Connecting a camera, step by step. Mevo-specific for now; when Camera Profiles
 * land this list comes from the active profile so each camera shows its own steps.
 */
private val CAMERA_STEPS = listOf(
    "Power on the camera and wait until it shows it's ready.",
    "On your phone, open the Mevo app and connect to the camera.",
    "In the Mevo app, tap Go Live to start the SRT broadcast — the camera only sends video while it's live.",
    "Check the camera Wi-Fi name, password, and SRT address in the fields below are correct.",
    "Tap Connect. The app joins the camera's Wi-Fi for you — if the tablet asks to connect to it, tap Connect/Allow.",
)

@Composable
private fun SetupGuide(modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 560.dp)
            .padding(16.dp)
            .background(Color(0xE6101418), RoundedCornerShape(16.dp))
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect the camera", color = Color.White, fontSize = 22.sp)
        CAMERA_STEPS.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${i + 1}",
                    color = Color(0xFF4C9AFF),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(20.dp),
                )
                Text(step, color = Color(0xFFE8EAED), fontSize = 16.sp)
            }
        }
        Text(
            "Your tablet's cellular stays on for streaming to YouTube — only the " +
                "camera feed uses the Mevo's Wi-Fi.",
            color = Color(0xFF9AA0A6),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
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
