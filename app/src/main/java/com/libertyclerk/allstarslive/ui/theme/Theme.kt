package com.libertyclerk.allstarslive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Liberty County AAA All-Stars — broadcast scorer runs dark by default (it lives
// on top of live video). Deep-navy + gold palette matches the web controller/viewer.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFBBF24),       // amber/gold — active tabs & primary buttons
    onPrimary = Color(0xFF241803),
    secondary = Color(0xFF5B97FF),     // accent blue (our team color)
    background = Color(0xFF0B1220),    // deep navy
    surface = Color(0xFF16223C),
    onBackground = Color(0xFFF6F9FC),
    onSurface = Color(0xFFF6F9FC),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
)

@Composable
fun AllStarsLiveTheme(
    darkTheme: Boolean = true || isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
