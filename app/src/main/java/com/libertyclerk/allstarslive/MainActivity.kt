package com.libertyclerk.allstarslive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Scoreboard
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libertyclerk.allstarslive.ingest.CompositorTestScreen
import com.libertyclerk.allstarslive.ingest.SrtIngestScreen
import com.libertyclerk.allstarslive.scorer.GameScorerScreen
import com.libertyclerk.allstarslive.scorer.createScorerWebView
import com.libertyclerk.allstarslive.stream.Broadcast
import com.libertyclerk.allstarslive.stream.GoLiveDialog
import com.libertyclerk.allstarslive.ui.theme.AllStarsLiveTheme
import kotlinx.coroutines.delay

/** Bottom-bar destinations. Game = scoring (next), Video = live ingest (working). */
// Persistent app sections only. Lineup is per-game, so it lives inside the scorer
// (Game tab -> New Game / Manage Teams), not as a global tab.
private enum class Tab(val label: String, val icon: ImageVector) {
    GAME("Game", Icons.Filled.Scoreboard),
    VIDEO("Video", Icons.Filled.Videocam),
}

private val NavBarColor = Color(0xFF0E1626)   // slightly lifted navy for the tab bar
private val NavHairline = Color(0xFF1E2A44)
private val Sage = Color(0xFF8C97A8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The scorer is handheld for a whole game on top of live video — keep the screen on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Bring the camera link up at launch so Go Live works from any tab (not just Video).
        com.libertyclerk.allstarslive.ingest.RtmpReceiverService.start(this, 1935)
        // Mic for the broadcast's audio track (YouTube needs audio to go live). Silence
        // is the fallback if denied, but real game sound is better — ask once.
        val needAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        val needNotif = android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        val ask = buildList {
            if (needAudio) add(android.Manifest.permission.RECORD_AUDIO)
            if (needNotif) add("android.permission.POST_NOTIFICATIONS")
        }
        if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 1)
        // Immersive: hide the status bar + nav/taskbar so the broadcast app is full-bleed.
        // Swipe from an edge to reveal them transiently; onWindowFocusChanged re-hides.
        hideSystemBars()

        setContent {
            AllStarsLiveTheme {
                // Branded splash: hold the A logo briefly, then fade to the app (the system
                // launch splash already shows the same logo on the dark bg, so it's seamless).
                var showSplash by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(1600); showSplash = false }
                var tabIndex by rememberSaveable { mutableStateOf(0) }
                val tabs = Tab.entries
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // Created once + kept alive so switching tabs doesn't reload a live game.
                val scorerWeb = androidx.compose.runtime.remember { createScorerWebView(ctx) }
                // During a game the tabs are seldom used — hide the bar and tuck it behind a
                // small floating "Menu" toggle so the scorer/video get the full screen.
                val inGame by AppUi.inGame.collectAsStateWithLifecycle()
                var tabsOpen by rememberSaveable { mutableStateOf(false) }
                val hideTabs = tabs[tabIndex] == Tab.GAME && inGame && !tabsOpen

                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        bottomBar = {
                            if (!hideTabs) AllStarsBottomBar(
                                tabs, tabIndex,
                                onSelect = { tabsOpen = false; tabIndex = it },
                                // In a game, let the operator collapse the bar back to the Menu pill.
                                onCollapse = if (inGame && tabs[tabIndex] == Tab.GAME) ({ tabsOpen = false }) else null,
                            )
                        },
                    ) { inner ->
                        Box(Modifier.fillMaxSize().padding(inner)) {
                            when (tabs[tabIndex]) {
                                Tab.GAME -> GameScorerScreen(scorerWeb)
                                Tab.VIDEO -> VideoTab()
                            }
                        }
                    }

                    // Floating toggle to bring the tab bar back during a game.
                    if (hideTabs) {
                        TabsPeekButton(
                            onClick = { tabsOpen = true },
                            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp),
                        )
                    }

                    // App-level "Start game stream" dialog — raised from the Video tab
                    // button OR the Game-page web button, both via Broadcast.requestDialog().
                    val showGoLive by Broadcast.showDialog.collectAsStateWithLifecycle()
                    val bcast by Broadcast.state.collectAsStateWithLifecycle()
                    if (showGoLive) {
                        GoLiveDialog(
                            initialTitle = bcast.title,
                            onStart = { t, p -> Broadcast.goLive(ctx, t, p); Broadcast.dismissDialog() },
                            onCancel = { Broadcast.dismissDialog() },
                        )
                    }

                    // Confirm before ending a live broadcast.
                    val showStop by Broadcast.showStopConfirm.collectAsStateWithLifecycle()
                    if (showStop) {
                        ConfirmDialog(
                            title = "End the broadcast?",
                            message = "Fans will stop seeing the game on YouTube.",
                            confirmLabel = "End broadcast",
                            onConfirm = { Broadcast.stop(); Broadcast.dismissStop() },
                            onCancel = { Broadcast.dismissStop() },
                        )
                    }

                    // Branded splash overlay — big A centered, wordmark near the bottom; fades out.
                    AnimatedVisibility(visible = showSplash, exit = fadeOut(animationSpec = tween(450))) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF0B0E13))) {
                            Image(
                                painter = painterResource(R.drawable.splash_logo),
                                contentDescription = "All-Stars Live",
                                modifier = Modifier.size(240.dp).align(Alignment.Center),
                            )
                            Row(
                                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp),
                            ) {
                                Text("ALL-STARS ", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = 2.sp)
                                Text("LIVE", color = Color(0xFFA3E635), fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = 2.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()   // re-hide after dialogs / transient swipe-reveal
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/**
 * GroundLink-style bottom bar: expanded, bordered pill buttons. Each tab always
 * shows its icon AND label; the selected one fills lime (no disappearing icon /
 * indicator swap like the default Material nav).
 */
@Composable
private fun AllStarsBottomBar(tabs: List<Tab>, selected: Int, onSelect: (Int) -> Unit, onCollapse: (() -> Unit)? = null) {
    val lime = MaterialTheme.colorScheme.primary
    val field = Color(0xFF0B0E13)
    val unselectedFill = Color(0xFF18223A)
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.background(NavBarColor).navigationBarsPadding()) {
        // Hairline accent above the bar for the broadcast look.
        Box(Modifier.fillMaxWidth().height(1.dp).background(NavHairline))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onCollapse != null) {
                Box(
                    Modifier
                        .clip(shape)
                        .background(unselectedFill)
                        .border(2.dp, NavHairline, shape)
                        .clickable(onClick = onCollapse)
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Hide tabs", tint = Sage, modifier = Modifier.size(20.dp))
                }
            }
            tabs.forEachIndexed { i, tab ->
                val sel = i == selected
                Row(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(if (sel) lime else unselectedFill)
                        .border(2.dp, if (sel) lime else NavHairline, shape)
                        .clickable { onSelect(i) }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (sel) field else Sage,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tab.label,
                        color = if (sel) field else Sage,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

/** Generic confirm dialog (e.g. ending the broadcast). */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC05080C)).clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .padding(24.dp)
                .background(Color(0xFF141A22), RoundedCornerShape(16.dp))
                .padding(22.dp)
                .clickable {},   // swallow taps so tapping the card doesn't cancel
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(message, color = Color(0xFF9AA0A6), fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onCancel) { Text("Cancel", color = Color(0xFF9AA0A6)) }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B5C), contentColor = Color.White),
                ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Tiny floating handle shown during a game to reveal the hidden tab bar. Kept small
 *  and icon-only so it doesn't sit on top of the scorer's bottom panels. */
@Composable
private fun TabsPeekButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(NavBarColor.copy(alpha = 0.92f))
            .border(1.dp, NavHairline, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Show tabs", tint = Sage, modifier = Modifier.size(18.dp))
    }
}

/** Video tab: live camera, or a test pattern (for streaming/recording without the camera).
 *  The mode switch lives INSIDE each screen (a top Row toggle wouldn't render reliably
 *  above the SurfaceView), so each screen offers a button to jump to the other. */
@Composable
private fun VideoTab() {
    var mode by rememberSaveable { mutableStateOf(0) }   // 0 = Camera, 1 = Test pattern
    Box(Modifier.fillMaxSize()) {
        if (mode == 0) SrtIngestScreen(onUseTestPattern = { mode = 1 })
        else CompositorTestScreen(onUseCamera = { mode = 0 })
    }
}

@Composable
private fun ComingSoon(icon: ImageVector, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color(0xFF5B6880),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            fontSize = 14.sp,
            color = Sage,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                "COMING SOON",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
        }
    }
}
