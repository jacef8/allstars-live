package com.libertyclerk.allstarslive.ingest

import android.content.Intent
import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    // Local recording state (offline fallback).
    val rec by Broadcast.recState.collectAsStateWithLifecycle()
    LaunchedEffect(rec.savedLocation) {
        if (rec.savedLocation.isNotEmpty()) Toast.makeText(ctx, "Recording saved to ${rec.savedLocation}", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(rec.error) {
        if (rec.error.isNotEmpty()) Toast.makeText(ctx, rec.error, Toast.LENGTH_LONG).show()
    }

    var surface by remember { mutableStateOf<Surface?>(null) }
    var showSetup by remember { mutableStateOf(false) }
    // Which camera the operator uses — picks the right setup instructions. Persisted.
    val prefs = remember { ctx.getSharedPreferences("allstars", android.content.Context.MODE_PRIVATE) }
    var cameraProfile by remember { mutableStateOf(prefs.getString("cam_profile", "mevo") ?: "mevo") }
    // How video is captured: "allInOne" = this device's own camera (films AND streams); "phoneCam"
    // = a second phone pushes RTMP to us; "external" = a Mevo/GoPro/etc. pushes RTMP. The last two
    // both receive RTMP — only allInOne uses our built-in camera.
    var setupMode by remember { mutableStateOf(prefs.getString("setup_mode", "external") ?: "external") }
    var lensBack by remember { mutableStateOf(prefs.getBoolean("lens_back", true)) }
    // Track YouTube connection so we can show a first-run prompt when it's not set up.
    var ytChannel by remember { mutableStateOf(prefs.getString("yt_channel", null)) }

    fun isDevice() = setupMode == "allInOne"

    fun startDevicePreview() {
        val s = surface ?: return
        RtmpHub.lensBack = lensBack
        // Off the main thread: starting GL + opening the camera blocks briefly.
        Thread { RtmpHub.attachPreview(s); RtmpHub.startDeviceCamera(ctx) }.start()
    }

    // Ask for CAMERA the first time the operator chooses all-in-one mode.
    val camPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDevicePreview()
        else Toast.makeText(ctx, "Camera permission is needed to film with this device", Toast.LENGTH_LONG).show()
    }

    fun connect() {
        val s = surface ?: return
        if (isDevice()) {
            if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startDevicePreview()
            } else {
                camPermLauncher.launch(android.Manifest.permission.CAMERA)
            }
        } else {
            // Off the main thread: start() spins up the GL compositor, decoder, and the RTMP listener.
            Thread { source.start("", s) }.start()
        }
    }

    fun applyMode(mode: String) {
        setupMode = mode
        val cap = if (mode == "allInOne") RtmpHub.MODE_DEVICE else RtmpHub.MODE_EXTERNAL
        RtmpHub.captureMode = cap
        prefs.edit().putString("setup_mode", mode).putString("capture_mode", cap).apply()
    }

    fun restart() {
        source.shutdown()          // detach preview + stop the RTMP receiver service (external)
        RtmpHub.stop()             // release compositor + device camera (idempotent)
        RtmpHub.captureMode = if (isDevice()) RtmpHub.MODE_DEVICE else RtmpHub.MODE_EXTERNAL
        connect()
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
            // Top-RIGHT so it doesn't sit under the "‹ Done" exit button (top-left, from MainActivity).
            LiveChip(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(14.dp))
        } else if (ytChannel != null) {
            // Camera not live yet, but YouTube is set up → show the "waiting for camera" status.
            // (When YouTube isn't connected we show the first-run prompt below INSTEAD, so the two
            // never overlap.)
            CameraStatus(stats.state, stats.message, isDevice(), onSetup = { showSetup = true }, onUseTestPattern = onUseTestPattern)
        }

        // Go Live control — reflects the shared broadcast state (synced with the Game
        // page). The dialog itself is raised app-level via Broadcast.requestDialog().
        GoLiveBar(
            phase = bcast.phase,
            status = bcast.status,
            cameraReady = playing,
            onGoLive = { Broadcast.requestDialog() },
            onEnd = { Broadcast.requestStop() },
            recording = rec.recording,
            recStartedAt = rec.startedAt,
            onRecord = { Broadcast.startRecording(ctx) },
            onStopRecord = { Broadcast.stopRecording() },
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
                setupMode = setupMode,
                onSetupMode = { applyMode(it); restart() },
                lensBack = lensBack,
                onLens = { lensBack = it; prefs.edit().putBoolean("lens_back", it).apply(); if (isDevice()) restart() },
                onRestart = { restart() },
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
private fun CameraStatus(state: IngestState, message: String, deviceMode: Boolean, onSetup: () -> Unit, onUseTestPattern: () -> Unit) {
    val looking = state == IngestState.CONNECTING || state == IngestState.BUFFERING || state == IngestState.RECONNECTING
    val title = when {
        deviceMode && looking -> "Starting this device's camera…"
        looking -> "Waiting for your camera…"
        else -> "Camera not connected"
    }
    // While waiting (external mode), RtmpVideoSource puts the exact publish URL in the message so the
    // operator can type it into the Mevo's Custom RTMP destination. In device mode there's no URL.
    val urlHint = if (deviceMode) "" else message.substringAfter("rtmp://", "").let { if (it.isNotEmpty()) "rtmp://$it" else "" }
    val sub = when {
        deviceMode && looking -> "Pointing this tablet's camera at the field…"
        looking -> "On your camera, set the RTMP destination to:"
        else -> "Tap Camera setup to get started."
    }
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
    recording: Boolean,
    recStartedAt: Long,
    onRecord: () -> Unit,
    onStopRecord: () -> Unit,
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
        } else if (recording) {
            // Recording locally (offline fallback): a REC pill with an elapsed timer + Stop.
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(recStartedAt) { while (true) { now = System.currentTimeMillis(); delay(500) } }
            val secs = ((now - recStartedAt) / 1000L).coerceAtLeast(0)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.background(Color(0xCC11161F), RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).background(Color(0xFFE11D2E), RoundedCornerShape(999.dp)))
                    Text("REC  %d:%02d".format(secs / 60, secs % 60), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = onStopRecord,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D2E), contentColor = Color.White),
                ) { Text("Stop recording", fontSize = 14.sp) }
            }
        } else if (cameraReady) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onGoLive,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B5C), contentColor = Color.White),
                ) { Text("● Go Live", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onRecord,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222B36), contentColor = Color.White),
                ) { Text("● Record", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
        }
        if (recording) {
            Text("Recording to this tablet — no internet needed. Upload later.", color = Color(0xFFB7C0CC), fontSize = 12.sp)
        } else if (status.isNotEmpty()) {
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
    setupMode: String,
    onSetupMode: (String) -> Unit,
    lensBack: Boolean,
    onLens: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val url = if (publishUrl.startsWith("rtmp://")) publishUrl else ""
    val profile = profileOf(profileId)
    val isDevice = setupMode == "allInOne"
    val isPhone = setupMode == "phoneCam"
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
                if (isDevice) "This tablet's own camera films AND streams — point it at the field and tap Go Live."
                else "The camera streams to this tablet; we add the scorebug and send it to YouTube.",
                color = Color(0xFF9AA0A6), fontSize = 13.sp,
            )

            // ----- Capture mode chooser: how is video being filmed? -----
            Text("HOW ARE YOU FILMING?", color = Color(0xFF6B7585), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "allInOne" to "This device",
                    "phoneCam" to "A phone",
                    "external" to "External camera",
                ).forEach { (id, label) ->
                    val on = id == setupMode
                    Text(
                        label,
                        color = if (on) Color(0xFF05080C) else Color(0xFFE8EAED),
                        fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .background(if (on) Color(0xFFA3E635) else Color(0xFF222B36), RoundedCornerShape(10.dp))
                            .clickable { if (!on) onSetupMode(id) }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                    )
                }
            }

            if (isDevice) {
                // ----- All-in-one: pick the lens; the rest is automatic -----
                Text("WHICH LENS", color = Color(0xFF6B7585), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "Back (recommended)", false to "Front (selfie)").forEach { (back, label) ->
                        val on = back == lensBack
                        Text(
                            label,
                            color = if (on) Color(0xFF05080C) else Color(0xFFE8EAED),
                            fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .background(if (on) Color(0xFFA3E635) else Color(0xFF222B36), RoundedCornerShape(10.dp))
                                .clickable { if (!on) onLens(back) }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                        )
                    }
                }
                Text(
                    "Prop the tablet on a tripod or fence with the lens facing the field. The live scorebug is added automatically.",
                    color = Color(0xFF9AA0A6), fontSize = 13.sp,
                )
            } else {
                // ----- External / phone-as-camera: instructions + the RTMP address to push to -----
                if (isPhone) {
                    PHONE_STEPS.forEachIndexed { i, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${i + 1}", color = Color(0xFF4C9AFF), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                            Text(step, color = Color(0xFFE8EAED), fontSize = 14.sp)
                        }
                    }
                } else {
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
                }
                // The address the camera/phone pushes RTMP to.
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
                                Toast.makeText(ctx, "Copied — paste into the camera app", Toast.LENGTH_SHORT).show()
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
                Text("Stream key: anything (e.g. live).", color = Color(0xFF6B7585), fontSize = 12.sp)

                // ----- Mevo: one tap to jump into the Mevo Multicam app (paste the address, Go Live) -----
                if (!isPhone && profile.id == "mevo") {
                    Button(
                        onClick = {
                            val launch = ctx.packageManager.getLaunchIntentForPackage("com.mevo.multicam")
                            try {
                                if (launch != null) {
                                    ctx.startActivity(launch)
                                } else {
                                    // Not installed → send them to the store listing.
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.mevo.multicam")))
                                }
                            } catch (e: Exception) {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.mevo.multicam")))
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "Couldn't open the Mevo app", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E6BE6)),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open the Mevo app", fontWeight = FontWeight.Bold, color = Color.White) }
                }

                // ----- Phone-as-camera: scan this in Larix Broadcaster to auto-configure -----
                if (isPhone && url.isNotEmpty()) {
                    val groveQr = remember(url) { QrUtil.encode(QrUtil.larixGrove(url), 560) }
                    Text(
                        "SCAN TO SET UP THE PHONE", color = Color(0xFF6B7585),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    )
                    Text(
                        "On the phone, install the free \"Larix Broadcaster\" app, scan this code in it (Settings → import / QR), then tap the red button. The phone becomes the camera — it must be on the same Wi-Fi as this tablet.",
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
            }

            // YouTube account lives here now (the old Settings tab is gone) — connect once
            // so Go Live can create the broadcast and stream without a stream key.
            com.libertyclerk.allstarslive.YouTubeAccountSection()

            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                Text(if (isDevice) "Restart camera" else "Restart camera link")
            }
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

/** Steps for the "use a phone as the camera" path (the QR does most of the work). */
private val PHONE_STEPS = listOf(
    "On the phone, install the free \"Larix Broadcaster\" app.",
    "Put the phone on the SAME Wi-Fi as this tablet.",
    "Scan the code below in Larix (it auto-fills the connection).",
    "Tap the red record button — the phone is now the camera.",
)

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
