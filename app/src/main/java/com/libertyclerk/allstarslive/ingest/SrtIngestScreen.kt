package com.libertyclerk.allstarslive.ingest

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Operator-facing camera screen. No backend on display: it **auto-connects** with
 * the saved camera settings and shows only a friendly status (or the live picture).
 * All the technical bits — Wi-Fi name/password, SRT URL, FPS/latency diagnostics —
 * live in a setup panel reached by **long-pressing** the screen (for whoever sets
 * up the hardware). The [VideoSource] is injected, so the transport can change
 * without touching this UI.
 */
@Composable
fun SrtIngestScreen(onUseTestPattern: () -> Unit = {}) {
    val ctx = LocalContext.current
    val source = remember { SrtVideoSource(ctx) }
    val settings = remember { CameraSettings(ctx) }
    val stats by source.stats.collectAsStateWithLifecycle()

    var surface by remember { mutableStateOf<Surface?>(null) }
    var showSetup by remember { mutableStateOf(false) }
    // Advanced (setup) fields — persisted, edited only in the setup panel.
    var url by remember { mutableStateOf(settings.url) }
    var ssid by remember { mutableStateOf(settings.wifiSsid) }
    var pass by remember { mutableStateOf(settings.wifiPassphrase) }

    fun connect() {
        source.wifiSsid = ssid
        source.wifiPassphrase = pass
        val s = surface ?: return
        // Off the main thread: start() spins up the GL compositor, decoder, and Wi-Fi
        // bind, which together block long enough to freeze the UI (and tab switches).
        Thread { source.start(url, s) }.start()
    }

    DisposableEffect(Unit) { onDispose { source.stop() } }

    val videoAspect = if (stats.widthPx > 0 && stats.heightPx > 0)
        stats.widthPx.toFloat() / stats.heightPx else 16f / 9f
    val playing = stats.state == IngestState.PLAYING

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Hidden setup access for admins: long-press anywhere — but NOT while the
            // setup sheet is open, or it steals taps/focus from the text fields.
            .then(
                if (showSetup) Modifier
                else Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { showSetup = true }) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(videoAspect),
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surface = holder.surface
                            connect()   // AUTO-CONNECT with saved settings — no operator action
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

        if (playing) {
            LiveChip(Modifier.align(Alignment.TopStart).padding(14.dp))
        } else {
            CameraStatus(stats.state, onSetup = { showSetup = true }, onUseTestPattern = onUseTestPattern)
        }

        if (showSetup) {
            CameraSetupSheet(
                stats = stats,
                ssid = ssid, pass = pass, url = url,
                onSsid = { ssid = it }, onPass = { pass = it }, onUrl = { url = it },
                onConnect = {
                    settings.url = url; settings.wifiSsid = ssid; settings.wifiPassphrase = pass
                    source.stop(); connect()
                },
                onClose = { showSetup = false },
            )
        }
    }
}

/** Friendly, jargon-free status shown when there's no live picture. */
@Composable
private fun CameraStatus(state: IngestState, onSetup: () -> Unit, onUseTestPattern: () -> Unit) {
    val looking = state == IngestState.CONNECTING || state == IngestState.BUFFERING || state == IngestState.RECONNECTING
    val title = if (looking) "Looking for your camera…" else "Camera not connected"
    val sub = if (looking) "Make sure the camera is on and streaming." else "Tap Camera setup to get started."
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(max = 460.dp).padding(24.dp),
    ) {
        Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color(0xFF5B6880), modifier = Modifier.size(56.dp))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = Color(0xFF9AA0A6), fontSize = 14.sp)
        TextButton(onClick = onSetup) {
            Text("Camera setup", color = Color(0xFFA3E635), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        // No camera yet? Jump to the test pattern to try streaming / recording.
        Button(
            onClick = onUseTestPattern,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C9AFF), contentColor = Color.White),
        ) { Text("Use test pattern instead", fontSize = 15.sp) }
    }
}

/** Small unobtrusive LIVE badge once the feed is up. */
@Composable
private fun LiveChip(modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0x99000000), RoundedCornerShape(999.dp)).padding(horizontal = 11.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Color(0xFF3FB950), RoundedCornerShape(999.dp)))
        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/** Admin setup: the only place the camera Wi-Fi / SRT URL and raw diagnostics live. */
@Composable
private fun CameraSetupSheet(
    stats: VideoStats,
    ssid: String, pass: String, url: String,
    onSsid: (String) -> Unit, onPass: (String) -> Unit, onUrl: (String) -> Unit,
    onConnect: () -> Unit, onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC05080C)).clickable(onClick = onClose).imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .padding(16.dp)
                .background(Color(0xFF141A22), RoundedCornerShape(16.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
                // Swallow taps so tapping inside the card doesn't close it (must be enabled to consume).
                .pointerInput(Unit) { detectTapGestures { } },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Camera setup", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            CAMERA_STEPS.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${i + 1}", color = Color(0xFF4C9AFF), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    Text(step, color = Color(0xFFE8EAED), fontSize = 14.sp)
                }
            }
            OutlinedTextField(ssid, onSsid, label = { Text("Camera Wi-Fi name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, onPass, label = { Text("Wi-Fi password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, onUrl, label = { Text("Camera stream URL (SRT)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Save & connect") }
            // Raw diagnostics — for setup/troubleshooting only.
            Text(
                "state ${stats.state}   ${"%.0f".format(stats.fps)} fps   ${stats.latencyMs} ms   " +
                    (if (stats.widthPx > 0) "${stats.widthPx}×${stats.heightPx}" else "—") +
                    "   frames ${stats.framesRendered}" +
                    (if (stats.message.isNotEmpty()) "\n${stats.message}" else ""),
                color = Color(0xFF6B7585), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            TextButton(onClick = onClose) { Text("Close", color = Color(0xFF9AA0A6)) }
        }
    }
}

/** Generic, camera-agnostic setup steps — per-camera instructions come with profiles later. */
private val CAMERA_STEPS = listOf(
    "Power on your camera and start its live stream (SRT). Some cameras need their own app or a button to begin.",
    "Connect this tablet to the camera's Wi-Fi network.",
    "Tap Save & connect to pull in the live feed.",
)
