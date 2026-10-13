package com.aca.agent

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Charcoal = Color(0xFF121218)
val Surface1 = Color(0xFF1B1B25)
val Surface2 = Color(0xFF242433)
val Purple = Color(0xFF9B7BFF)
val Blue = Color(0xFF4DA3FF)
val Ok = Color(0xFF4CD08A)
val Warn = Color(0xFFFFC857)
val Bad = Color(0xFFFF6B6B)

private val Scheme = darkColorScheme(
    primary = Purple, onPrimary = Color(0xFF14102A),
    secondary = Blue, onSecondary = Color(0xFF05182B),
    background = Charcoal, onBackground = Color(0xFFE8E8F2),
    surface = Surface1, onSurface = Color(0xFFE8E8F2),
    surfaceVariant = Surface2, onSurfaceVariant = Color(0xFFB4B4C8),
    error = Bad, outline = Color(0xFF45455E)
)

/** Always dark: the design brief asks for a premium dark interface. */
@Composable
fun ACATheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val sys = isSystemInDarkTheme()
    MaterialTheme(colorScheme = Scheme, content = content)
}
