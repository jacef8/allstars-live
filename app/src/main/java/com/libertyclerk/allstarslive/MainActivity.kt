package com.libertyclerk.allstarslive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.Scoreboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.libertyclerk.allstarslive.ingest.CompositorTestScreen
import com.libertyclerk.allstarslive.ingest.SrtIngestScreen
import com.libertyclerk.allstarslive.scorer.GameScorerScreen
import com.libertyclerk.allstarslive.scorer.createScorerWebView
import com.libertyclerk.allstarslive.ui.theme.AllStarsLiveTheme

/** Bottom-bar destinations. Game = scoring (next), Video = live ingest (working). */
// Persistent app sections only. Lineup is per-game, so it lives inside the scorer
// (Game tab -> New Game / Manage Teams), not as a global tab.
private enum class Tab(val label: String, val icon: ImageVector) {
    GAME("Game", Icons.Filled.Scoreboard),
    VIDEO("Video", Icons.Filled.Videocam),
    SETTINGS("Settings", Icons.Filled.Settings),
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
        // Keep the system bars VISIBLE so the Scaffold insets push our top toggle /
        // bottom tabs clear of the screen edges + camera cutout (hiding them clipped UI).

        setContent {
            AllStarsLiveTheme {
                var tabIndex by rememberSaveable { mutableStateOf(0) }
                val tabs = Tab.entries
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // Created once + kept alive so switching tabs doesn't reload a live game.
                val scorerWeb = androidx.compose.runtime.remember { createScorerWebView(ctx) }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = { AllStarsBottomBar(tabs, tabIndex, onSelect = { tabIndex = it }) },
                ) { inner ->
                    Box(Modifier.fillMaxSize().padding(inner)) {
                        when (tabs[tabIndex]) {
                            Tab.GAME -> GameScorerScreen(scorerWeb)
                            Tab.VIDEO -> VideoTab()
                            Tab.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }

}

/**
 * GroundLink-style bottom bar: expanded, bordered pill buttons. Each tab always
 * shows its icon AND label; the selected one fills lime (no disappearing icon /
 * indicator swap like the default Material nav).
 */
@Composable
private fun AllStarsBottomBar(tabs: List<Tab>, selected: Int, onSelect: (Int) -> Unit) {
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
        ) {
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
