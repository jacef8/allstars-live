package com.libertyclerk.allstarslive.ingest

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libertyclerk.allstarslive.stream.Broadcast

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
    // RTMP-push: the Mevo publishes to us (rtmp://<tablet-ip>:1935/live). SRT pull is
    // retired for the Mevo — it only serves SRT while streaming to a network
    // destination, and "Go Live" forces picking one. See RtmpVideoSource.
    val source = remember { RtmpVideoSource(ctx) }
    val stats by source.stats.collectAsStateWithLifecycle()
    // Shared YouTube broadcast state — same source of truth as the Game page.
    val bcast by Broadcast.state.collectAsStateWithLifecycle()

    var surface by remember { mutableStateOf<Surface?>(null) }
    var showSetup by remember { mutableStateOf(false) }
    // Which camera the operator uses — picks the right setup instructions. Persisted.
    val prefs = remember { ctx.getSharedPreferences("allstars", android.content.Context.MODE_PRIVATE) }
    var cameraProfile by remember { mutableStateOf(prefs.getString("cam_profile", "mevo") ?: "mevo") }
    // Track YouTube connection so we can show a first-run prompt when it's not set up.
    var ytChannel by remember { mutableStateOf(prefs.getString("yt_channel", null)) }

    fun connect() {
        val s = surface ?: return
        // Off the main thread: start() spins up the GL compositor, decoder, and the
        // RTMP listener, which together block long enough to freeze the UI.
        Thread { source.start("", s) }.start()
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
        } else if (ytChannel != null) {
            // Camera not live yet, but YouTube is set up → show the "waiting for camera" status.
            // (When YouTube isn't connected we show the first-run prompt below INSTEAD, so the two
            // never overlap.)
            CameraStatus(stats.state, stats.message, onSetup = { showSetup = true }, onUseTestPattern = onUseTestPattern)
        }

        // Go Live control — reflects the shared broadcast state (synced with the Game
        // page). The dialog itself is raised app-level via Broadcast.requestDialog().
        GoLiveBar(
            phase = bcast.phase,
            status = bcast.status,
            cameraReady = playing,
            onGoLive = { Broadcast.requestDialog() },
            onEnd = { Broadcast.requestStop() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )

        // First-run prompt: YouTube not connected and camera isn't already streaming.
        // Show a clear call-to-action rather than hiding setup behind a long-press.
        if (!playing && ytChannel == null) {
            YouTubeSetupPrompt(onSetup = { showSetup = true })
        }

        if (showSetup) {
            CameraSetupSheet(
                stats = stats,
                publishUrl = RtmpHub.publishHint,
                profileId = cameraProfile,
                onProfile = { cameraProfile = it; prefs.edit().putString("cam_profile", it).apply() },
                onRestart = { source.shutdown(); connect() },
                onClose = {
                    showSetup = false
                    // Refresh YouTube status in case the user just connected.
                    ytChannel = prefs.getString("yt_channel", null)
                },
            )
        }
    }
}

/** Shown when YouTube has never been connected — guides the user to set it up. */
@Composable
private fun YouTubeSetupPrompt(onSetup: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(max = 460.dp)
            .padding(32.dp),
    ) {
        Icon(
            Icons.Filled.Videocam, contentDescription = null,
            tint = Color(0xFF5B6880),
            modifier = Modifier.size(52.dp),
        )
        Text("Set up your stream", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Connect your YouTube account and camera once, then tap Go Live at game time.",
            color = Color(0xFF9AA0A6), fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onSetup,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA3E635), contentColor = Color(0xFF0B0E13)),
        ) { Text("Get started →", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Friendly, jargon-free status shown when there's no live picture. */
@Composable
private fun CameraStatus(state: IngestState, message: String, onSetup: () -> Unit, onUseTestPattern: () -> Unit) {
    val looking = state == IngestState.CONNECTING || state == IngestState.BUFFERING || state == IngestState.RECONNECTING
    val title = if (looking) "Waiting for your camera…" else "Camera not connected"
    // While waiting, RtmpVideoSource puts the exact publish URL in the message so the
    // operator can type it into the Mevo's Custom RTMP destination.
    val urlHint = message.substringAfter("rtmp://", "").let { if (it.isNotEmpty()) "rtmp://$it" else "" }
    val sub = if (looking) "On your camera, set the RTMP destination to:" else "Tap Camera setup to get started."
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(max = 460.dp).padding(24.dp),
    ) {
        Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color(0xFF5B6880), modifier = Modifier.size(56.dp))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = Color(0xFF9AA0A6), fontSize = 14.sp)
        if (looking && urlHint.isNotEmpty()) {
            val clipboard = LocalClipboardManager.current
            val ctx = LocalContext.current
            // Tap to copy — the operator pastes this straight into the Mevo's RTMP field.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xFF101720), RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(urlHint))
                        Toast.makeText(ctx, "Copied — paste into your camera app", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    urlHint,
                    color = Color(0xFFA3E635), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = Color(0xFF9AA0A6), modifier = Modifier.size(18.dp))
            }
            Text("Tap to copy  ·  Stream key: any (e.g. live)", color = Color(0xFF6B7585), fontSize = 12.sp)
        }
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

/** Go Live / End-broadcast control, driven by the shared [Broadcast] state. */
@Composable
private fun GoLiveBar(
    phase: Broadcast.Phase,
    status: String,
    cameraReady: Boolean,
    onGoLive: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = phase == Broadcast.Phase.LIVE || phase == Broadcast.Phase.STARTING
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (active) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.background(Color(0xCC11161F), RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).background(Color(0xFFFF3B5C), RoundedCornerShape(999.dp)))
                    Text(
                        if (phase == Broadcast.Phase.LIVE) "LIVE on YouTube" else "Going live…",
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Button(
                    onClick = onEnd,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B5C), contentColor = Color.White),
                ) { Text("End broadcast", fontSize = 14.sp) }
            }
        } else if (cameraReady) {
            Button(
                onClick = onGoLive,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B5C), contentColor = Color.White),
            ) { Text("● Go Live", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        if (status.isNotEmpty()) {
            Text(status, color = if (phase == Broadcast.Phase.ERROR) Color(0xFFFF6B6B) else Color(0xFFB7C0CC), fontSize = 12.sp)
        }
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

/** Admin setup: pick the camera, get its specific instructions + the RTMP address. */
@Composable
private fun CameraSetupSheet(
    stats: VideoStats,
    publishUrl: String,
    profileId: String,
    onProfile: (String) -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val url = if (publishUrl.startsWith("rtmp://")) publishUrl else ""
    val profile = profileOf(profileId)
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
            Text(
                "The camera streams to this tablet; we add the scorebug and send it to YouTube.",
                color = Color(0xFF9AA0A6), fontSize = 13.sp,
            )
            // Camera picker — instructions below adapt to the selected camera.
            Text("YOUR CAMERA", color = Color(0xFF6B7585), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CAMERA_PROFILES.forEach { p ->
                    val on = p.id == profileId
                    Text(
                        p.name,
                        color = if (on) Color(0xFF05080C) else Color(0xFFE8EAED),
                        fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .background(if (on) Color(0xFFA3E635) else Color(0xFF222B36), RoundedCornerShape(999.dp))
                            .clickable { onProfile(p.id) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            profile.steps.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${i + 1}", color = Color(0xFF4C9AFF), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    Text(step, color = Color(0xFFE8EAED), fontSize = 14.sp)
                }
            }
            // The address to type into the Mevo's Custom RTMP destination.
            Text(
                "CAMERA RTMP ADDRESS", color = Color(0xFF6B7585),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101720), RoundedCornerShape(8.dp))
                    .clickable {
                        if (url.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(url))
                            Toast.makeText(ctx, "Copied — paste into the Mevo", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    url.ifEmpty { "Connect the tablet to the camera's Wi-Fi first…" },
                    color = if (url.isNotEmpty()) Color(0xFFA3E635) else Color(0xFF9AA0A6),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (url.isNotEmpty()) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = Color(0xFF9AA0A6), modifier = Modifier.size(18.dp))
                }
            }
            Text("Stream key: anything (e.g. live).  SRT must be OFF in the Mevo.", color = Color(0xFF6B7585), fontSize = 12.sp)

            // ----- Use a phone as the camera: scan this in Larix Broadcaster to auto-configure -----
            if (url.isNotEmpty()) {
                val groveQr = remember(url) { QrUtil.encode(QrUtil.larixGrove(url), 560) }
                Text(
                    "USE A PHONE AS THE CAMERA", color = Color(0xFF6B7585),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                )
                Text(
                    "No camera? On a second phone, install the free \"Larix Broadcaster\" app, scan this code in it (Settings → Connections → import / QR), then tap the red button. The phone becomes the camera — it must be on the same Wi-Fi as this tablet.",
                    color = Color(0xFF9AA0A6), fontSize = 13.sp,
                )
                if (groveQr != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = groveQr.asImageBitmap(),
                            contentDescription = "Scan in Larix Broadcaster to use this phone as the camera",
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color.White, RoundedCornerShape(10.dp))
                                .padding(8.dp),
                        )
                    }
                }
            }

            // YouTube account lives here now (the old Settings tab is gone) — connect once
            // so Go Live can create the broadcast and stream without a stream key.
            com.libertyclerk.allstarslive.YouTubeAccountSection()

            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Restart camera link") }
            // Raw diagnostics — for setup/troubleshooting only.
            Text(
                "state ${stats.state}   ${"%.0f".format(stats.fps)} fps   " +
                    (if (stats.widthPx > 0) "${stats.widthPx}×${stats.heightPx}" else "—") +
                    "   frames ${stats.framesRendered}",
                color = Color(0xFF6B7585), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            TextButton(onClick = onClose) { Text("Close", color = Color(0xFF9AA0A6)) }
        }
    }
}

/** A camera type + its RTMP-push setup steps. All push to the same address below. */
private data class CameraProfile(val id: String, val name: String, val steps: List<String>)

private fun profileOf(id: String): CameraProfile =
    CAMERA_PROFILES.firstOrNull { it.id == id } ?: CAMERA_PROFILES.first()

/** Per-camera instructions. Every camera ends up pushing RTMP to this tablet. */
private val CAMERA_PROFILES = listOf(
    CameraProfile(
        "mevo", "Mevo",
        listOf(
            "Connect this tablet to the Mevo's Wi-Fi (Android Settings → Wi-Fi).",
            "In the Mevo app: open streaming settings and turn SRT OFF.",
            "Add a Custom RTMP destination and paste the address below (stream key: any).",
            "Press Go Live and choose that Custom RTMP destination.",
        ),
    ),
    CameraProfile(
        "gopro", "GoPro",
        listOf(
            "Put this tablet and the GoPro/Quik app on the same Wi-Fi.",
            "In GoPro Quik: control the camera → Live → set the platform to RTMP / Other.",
            "Paste the address below as the RTMP URL (stream key: any).",
            "Start the live stream.",
        ),
    ),
    CameraProfile(
        "dji", "DJI",
        listOf(
            "Put this tablet and the DJI app (Mimo / Fly) on the same Wi-Fi.",
            "In the DJI app: Live Streaming → choose RTMP / Custom (Platform).",
            "Paste the address below as the RTMP URL.",
            "Start the live stream.",
        ),
    ),
    CameraProfile(
        "phone", "Phone",
        listOf(
            "Install an RTMP camera app (e.g. Larix Broadcaster) on the phone.",
            "Put the phone on the same Wi-Fi as this tablet.",
            "Set its connection / server URL to the address below (stream key: any).",
            "Start streaming from the app.",
        ),
    ),
    CameraProfile(
        "other", "Other",
        listOf(
            "Put the camera/encoder on the same Wi-Fi as this tablet.",
            "In its live-stream settings choose Custom RTMP.",
            "Use the address below as the RTMP server URL (stream key: any).",
            "Start the stream.",
        ),
    ),
)
