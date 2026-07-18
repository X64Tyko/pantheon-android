package com.pantheon.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Approximate sRGB equivalents of hades/src/index.css's core --hds-* oklch
// tokens (same values app/src/main/res/values/colors.xml uses for the
// pre-Compose window background) — this is the actual Compose ColorScheme,
// which MaterialTheme's own default (light M3 baseline) would otherwise
// render instead. Not the source of truth; a real oklch-aware read of
// hades/public/tv-tokens.json at runtime is the honest follow-up, same "not
// yet wired up" gap noted throughout this project.
private val PantheonColors = darkColorScheme(
    background = Color(0xFF1B1C29),
    surface = Color(0xFF1B1C29),
    primary = Color(0xFFE0B84E),
    onPrimary = Color(0xFF201A08),
    secondary = Color(0xFF8A7FD1),
    onBackground = Color(0xFFEEEEF2),
    onSurface = Color(0xFFEEEEF2),
    error = Color(0xFFCF6679),
)

@Composable
fun PantheonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PantheonColors, content = content)
}
