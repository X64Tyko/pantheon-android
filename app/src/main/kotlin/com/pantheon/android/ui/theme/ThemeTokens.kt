package com.pantheon.android.ui.theme

import androidx.compose.ui.graphics.Color
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
