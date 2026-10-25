package io.github.audiosplit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFF9BCBFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF16476E),
    onPrimaryContainer = Color(0xFFD3E7FF),
    secondary = Color(0xFF9EDCC8),
    secondaryContainer = Color(0xFF163D35),
    onSecondaryContainer = Color(0xFFC3F3E2),
    background = Color(0xFF0A1220),
    onBackground = Color(0xFFE4ECF7),
    surface = Color(0xFF111D2D),
    surfaceVariant = Color(0xFF1E2C3F),
    onSurface = Color(0xFFE4ECF7),
    onSurfaceVariant = Color(0xFFB6C4D6),
    outline = Color(0xFF63758A),
    outlineVariant = Color(0xFF304154),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF57271F),
    onErrorContainer = Color(0xFFFFDAD4),
)

@Composable
fun AudioSplitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
