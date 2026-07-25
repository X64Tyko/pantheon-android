package com.pantheon.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.TvTheme

// TV counterpart of the mobile flavor's PantheonTheme.kt — same real
// manifest-token reads (see ThemeTokens.kt), but androidx.tv.material3.
// ColorScheme is a genuinely different (incompatible) type from
// androidx.compose.material3.ColorScheme, not just a differently-styled
// version of the same theme, hence a fully separate flavor-specific file
// rather than shared logic.
//
// Wraps BOTH theme systems, not just tv.material3's: ConnectScreen/LoginScreen
// are shared with the mobile flavor and use plain androidx.compose.material3
// widgets (a deliberate scope call for this pass — see PantheonNavHost.kt's
// comment), which read theming from compose.material3.MaterialTheme only:
// tv.material3.MaterialTheme is a separate CompositionLocal tree and doesn't
// propagate to them. Without the outer wrapper here those two screens render
// Compose's default light theme on the tv flavor even though HomeScreen
// itself (genuinely tv-material) is correctly dark-themed — caught on a real
// device, not assumed.
@Composable
fun PantheonTheme(apiClient: ApiClient, content: @Composable () -> Unit) {
    var theme by remember { mutableStateOf<TvTheme?>(null) }
    LaunchedEffect(Unit) {
        theme = runCatching { apiClient.service.getTvManifest().theme }.getOrNull()
    }

    val background = theme.color("hds-bg", Color(0xFF1B1C29))
    val surface = theme.color("hds-bg-2", Color(0xFF1B1C29))
    val primary = theme.color("hds-gold", Color(0xFFE0B84E))
    val onPrimary = theme.color("hds-txt-on-gold", Color(0xFF201A08))
    val secondary = theme.color("hds-violet", Color(0xFF8A7FD1))
    val onSurfaceText = theme.color("hds-txt", Color(0xFFEEEEF2))
    val errorColor = theme.color("hds-match-red", Color(0xFFCF6679))

    val tvColors = darkColorScheme(
        background = background,
        surface = surface,
        primary = primary,
        onPrimary = onPrimary,
        secondary = secondary,
        onBackground = onSurfaceText,
        onSurface = onSurfaceText,
        error = errorColor,
    )
    val material3Colors = androidx.compose.material3.darkColorScheme(
        background = background,
        surface = surface,
        primary = primary,
        onPrimary = onPrimary,
        secondary = secondary,
        onBackground = onSurfaceText,
        onSurface = onSurfaceText,
        error = errorColor,
    )

    androidx.compose.material3.MaterialTheme(colorScheme = material3Colors) {
        androidx.tv.material3.MaterialTheme(colorScheme = tvColors, content = content)
    }
}
