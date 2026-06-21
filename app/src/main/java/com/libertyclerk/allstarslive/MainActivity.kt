package com.libertyclerk.allstarslive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Scoreboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.libertyclerk.allstarslive.ingest.SrtIngestScreen
import com.libertyclerk.allstarslive.ui.theme.AllStarsLiveTheme

/** Bottom-bar destinations. Game = scoring (next), Video = live ingest (working). */
private enum class Tab(val label: String, val icon: ImageVector) {
    GAME("Game", Icons.Filled.Scoreboard),
    VIDEO("Video", Icons.Filled.Videocam),
    LINEUP("Lineup", Icons.Filled.People),
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
        // Full-screen broadcast surface: hide the status + nav bars; a swipe from an
        // edge brings them back transiently, then they auto-hide again.
        hideSystemBars()

        setContent {
            AllStarsLiveTheme {
                var tabIndex by rememberSaveable { mutableStateOf(0) }
                val tabs = Tab.entries

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = { AllStarsBottomBar(tabs, tabIndex, onSelect = { tabIndex = it }) },
                ) { inner ->
                    Box(Modifier.fillMaxSize().padding(inner)) {
                        when (tabs[tabIndex]) {
                            Tab.GAME -> ComingSoon(
                                Icons.Filled.Scoreboard,
                                "Live scoring",
                                "Tap-to-score the game from here — porting the web scorer into the app is next.",
                            )
                            Tab.VIDEO -> SrtIngestScreen()
                            Tab.LINEUP -> ComingSoon(
                                Icons.Filled.People,
                                "Lineup",
                                "Set the batting order and field positions for the All-Stars here.",
                            )
                            Tab.SETTINGS -> ComingSoon(
                                Icons.Filled.Settings,
                                "Settings",
                                "Camera Wi-Fi, stream, and game options will live here.",
                            )
                        }
                    }
                }
            }
        }
    }

    // Re-hide the bars after they were shown by a swipe, the soft keyboard, or a dialog.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun AllStarsBottomBar(tabs: List<Tab>, selected: Int, onSelect: (Int) -> Unit) {
    val gold = MaterialTheme.colorScheme.primary
    Column {
        // Hairline accent above the bar for the broadcast look.
        Box(Modifier.fillMaxWidth().height(1.dp).background(NavHairline))
        NavigationBar(containerColor = NavBarColor, tonalElevation = 0.dp) {
            tabs.forEachIndexed { i, tab ->
                NavigationBarItem(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = gold,
                        selectedTextColor = gold,
                        indicatorColor = gold.copy(alpha = 0.16f),
                        unselectedIconColor = Sage,
                        unselectedTextColor = Sage,
                    ),
                )
            }
        }
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
