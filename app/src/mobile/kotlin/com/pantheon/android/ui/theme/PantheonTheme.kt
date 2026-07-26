package com.pantheon.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.TvTheme

// Real oklch-derived hex values read from the manifest's theme.tokens.colors
// (hades/scripts/generate-tv-tokens.mjs's output, served by
// GET /api/tv/manifest) — "styling comes from the manifest, not a per-client
// hardcoded color guess". Provides both the stock Material3 ColorScheme
// (consumed implicitly by stock components) and the richer LocalPantheonColors
// (PantheonColors.kt) every screen's own hand-rolled UI reads explicitly —
// the fallback Color literals live in DefaultPantheonColors, not here, so
// there's exactly one place they're defined.
@Composable
fun PantheonTheme(apiClient: ApiClient, content: @Composable () -> Unit) {
    var theme by remember { mutableStateOf<TvTheme?>(null) }
    LaunchedEffect(Unit) {
        theme = runCatching { apiClient.service.getTvManifest().theme }.getOrNull()
    }

    val pantheonColors = pantheonColorsFromTheme(theme)
    val colors = darkColorScheme(
        background = pantheonColors.bg,
        surface = pantheonColors.bg2,
        primary = pantheonColors.gold,
        onPrimary = pantheonColors.txtOnGold,
        secondary = pantheonColors.violet,
        onBackground = pantheonColors.txt,
        onSurface = pantheonColors.txt,
        error = pantheonColors.matchRed,
    )
    CompositionLocalProvider(LocalPantheonColors provides pantheonColors) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
