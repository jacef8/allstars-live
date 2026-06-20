package com.libertyclerk.allstarslive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Liberty County AAA All-Stars — broadcast scorer runs dark by default
// (it lives on top of live video), so we lean on the dark scheme.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F9CFF),
    onPrimary = Color.White,
    background = Color(0xFF0B0B0D),
    surface = Color(0xFF15161A),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
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
