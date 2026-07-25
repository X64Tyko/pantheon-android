package com.pantheon.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.TvTheme

// Real oklch-derived hex values read from the manifest's theme.tokens.colors
// (hades/scripts/generate-tv-tokens.mjs's output, served by
// GET /api/tv/manifest) — "styling comes from the manifest, not a per-client
// hardcoded color guess". The Color(...) literals below are only the
// fallback for a manifest that hasn't loaded yet or predates the theme field
// entirely (see ThemeTokens.kt's own comment), not the source of truth.
@Composable
fun PantheonTheme(apiClient: ApiClient, content: @Composable () -> Unit) {
    var theme by remember { mutableStateOf<TvTheme?>(null) }
    LaunchedEffect(Unit) {
        theme = runCatching { apiClient.service.getTvManifest().theme }.getOrNull()
    }

    val colors = darkColorScheme(
        background = theme.color("hds-bg", Color(0xFF1B1C29)),
        surface = theme.color("hds-bg-2", Color(0xFF1B1C29)),
        primary = theme.color("hds-gold", Color(0xFFE0B84E)),
        onPrimary = theme.color("hds-txt-on-gold", Color(0xFF201A08)),
        secondary = theme.color("hds-violet", Color(0xFF8A7FD1)),
        onBackground = theme.color("hds-txt", Color(0xFFEEEEF2)),
        onSurface = theme.color("hds-txt", Color(0xFFEEEEF2)),
        error = theme.color("hds-match-red", Color(0xFFCF6679)),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
