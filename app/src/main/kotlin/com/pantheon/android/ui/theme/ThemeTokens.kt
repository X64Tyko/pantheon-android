package com.pantheon.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pantheon.android.api.dto.TvTheme

// Parses a manifest theme color's hex ("#RRGGBB" or CSS-order "#RRGGBBAA",
// alpha LAST — see generate-tv-tokens.mjs's toHex()) into a Compose Color.
// Deliberately not android.graphics.Color.parseColor: that expects Android's
// own #AARRGGBB (alpha FIRST) packing, which would silently misread an
// 8-digit CSS hex's bytes as the wrong channels.
fun parseCssHex(hex: String): Color? = try {
    val h = hex.removePrefix("#")
    when (h.length) {
        6 -> Color(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
        8 -> Color(
            h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16),
            h.substring(4, 6).toInt(16), h.substring(6, 8).toInt(16),
        )
        else -> null
    }
} catch (e: NumberFormatException) { null }

// Resolves a design-token color to a Compose Color, falling back when the
// manifest hasn't loaded yet, has no theme (fresh checkout before the
// generator has ever run), or doesn't have this specific token — never a
// hard failure, callers always get something to render.
fun TvTheme?.color(token: String, fallback: Color): Color =
    this?.tokens?.colors?.get(token)?.hex?.let(::parseCssHex) ?: fallback

// Parses a "12px"/"12.5px" spacing/radii/sizing token value into a Compose
// Dp — 1 CSS px is treated as 1 dp, the same assumption every other token
// here already makes (these are declared design-system constants, not
// something that should scale with a specific device's actual pixel
// density). Non-px values (there aren't any today, but a future token using
// e.g. "1rem" would otherwise be silently misread as a huge Dp count) fall
// back rather than guess.
fun parseCssPxDp(value: String): Dp? =
    if (value.endsWith("px")) value.removeSuffix("px").toFloatOrNull()?.dp else null

// Parses a "0.2s"/"200ms" transition-duration token into whole milliseconds
// for Compose's AnimationSpec (durationMillis: Int).
fun parseCssDurationMs(value: String): Int? = when {
    value.endsWith("ms") -> value.removeSuffix("ms").toFloatOrNull()?.toInt()
    value.endsWith("s")  -> value.removeSuffix("s").toFloatOrNull()?.let { (it * 1000).toInt() }
    else -> null
}

fun TvTheme?.spacingDp(token: String, fallback: Dp): Dp =
    this?.tokens?.spacing?.get(token)?.let(::parseCssPxDp) ?: fallback

fun TvTheme?.radiusDp(token: String, fallback: Dp): Dp =
    this?.tokens?.radii?.get(token)?.let(::parseCssPxDp) ?: fallback

fun TvTheme?.sizingDp(token: String, fallback: Dp): Dp =
    this?.tokens?.sizing?.get(token)?.let(::parseCssPxDp) ?: fallback

fun TvTheme?.transitionMs(token: String, fallback: Int): Int =
    this?.tokens?.transitions?.get(token)?.let(::parseCssDurationMs) ?: fallback
