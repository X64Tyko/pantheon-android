package com.pantheon.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme

// TV counterpart of the mobile flavor's PantheonTheme.kt — same HDS color
// approximations, but androidx.tv.material3.ColorScheme is a genuinely
// different (incompatible) type from androidx.compose.material3.ColorScheme,
// not just a differently-styled version of the same theme, hence a fully
// separate flavor-specific file rather than shared logic.
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
private val PantheonTvColors = darkColorScheme(
    background = Color(0xFF1B1C29),
    surface = Color(0xFF1B1C29),
    primary = Color(0xFFE0B84E),
    onPrimary = Color(0xFF201A08),
    secondary = Color(0xFF8A7FD1),
    onBackground = Color(0xFFEEEEF2),
    onSurface = Color(0xFFEEEEF2),
    error = Color(0xFFCF6679),
)

private val PantheonMaterial3Colors = androidx.compose.material3.darkColorScheme(
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
    androidx.compose.material3.MaterialTheme(colorScheme = PantheonMaterial3Colors) {
        androidx.tv.material3.MaterialTheme(colorScheme = PantheonTvColors, content = content)
    }
}
