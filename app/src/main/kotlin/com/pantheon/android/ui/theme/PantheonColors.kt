package com.pantheon.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.pantheon.android.api.dto.TvTheme

// The actual HDS (Hades Design System) palette every screen in this app
// should be pulling from — "styling comes from the manifest, not a
// per-client hardcoded color guess" (PantheonTheme.kt's own header comment,
// which until now only ever reached the handful of Material3 ColorScheme
// slots (background/primary/onPrimary/etc.) that stock Material3/tv-material3
// components consume implicitly; every screen's own hand-rolled UI read
// none of that and defined its own local `Color(0x...)` constants instead,
// so a manifest theme change never actually reached them). This is the
// richer, HDS-token-shaped set every screen should read explicitly via
// LocalPantheonColors.current — see hades/src/index.css's own --hds-* custom
// properties (generate-tv-tokens.mjs's source) for the canonical token list
// this mirrors. Deliberately not shoehorned into Material3's 8-or-so
// semantic slots: HDS has multiple background/text tiers (bg/bg-2/bg-3/bg-4,
// txt/txt-2/txt-3) that Material3's single background/onBackground pair
// can't represent without losing information.
data class PantheonColors(
    val bg: Color, val bg2: Color, val bg3: Color, val bg4: Color,
    val txt: Color, val txt2: Color, val txt3: Color,
    val gold: Color, val gold2: Color, val txtOnGold: Color,
    val violet: Color, val violetDeep: Color,
    val matchGreen: Color, val matchAmber: Color, val matchRed: Color, val matchGrey: Color,
    val glassBorder: Color, val discoverAccent: Color,
)

// Fallback palette — today's already-shipped, hardware-verified hex values
// (the exact constants every screen used to define locally), used whenever
// the manifest hasn't loaded yet or predates a given token. Not the source
// of truth; see PantheonTheme.kt for the real manifest-driven read.
val DefaultPantheonColors = PantheonColors(
    bg = Color(0xFF1B1C29), bg2 = Color(0xFF1F2033), bg3 = Color(0xFF232438), bg4 = Color(0xFF2B2C42),
    txt = Color(0xFFEEEEF2), txt2 = Color(0xFFB5B5C4), txt3 = Color(0xFF8C8C99),
    gold = Color(0xFFE0B84E), gold2 = Color(0xFFCBA23F), txtOnGold = Color(0xFF201A08),
    violet = Color(0xFF9991EB), violetDeep = Color(0xFF7C6BD6),
    matchGreen = Color(0xFF6FC48A), matchAmber = Color(0xFFD9AC4E), matchRed = Color(0xFFCF6679), matchGrey = Color(0xFF6B6B78),
    glassBorder = Color(0xFF3F3C47), discoverAccent = Color(0xFF3A7CA5),
)

val LocalPantheonColors = staticCompositionLocalOf { DefaultPantheonColors }

// Resolves every slot above against the manifest's theme.tokens.colors (see
// ThemeTokens.kt's TvTheme?.color helper for the per-token fallback rule),
// falling back to DefaultPantheonColors's matching field wherever a token is
// missing. Framework-agnostic (plain Color, not tied to either
// compose.material3 or tv.material3's own ColorScheme type) so both flavors'
// PantheonTheme.kt can share this one construction.
@Composable
fun pantheonColorsFromTheme(theme: TvTheme?): PantheonColors {
    val d = DefaultPantheonColors
    return PantheonColors(
        bg = theme.color("hds-bg", d.bg), bg2 = theme.color("hds-bg-2", d.bg2),
        bg3 = theme.color("hds-bg-3", d.bg3), bg4 = theme.color("hds-bg-4", d.bg4),
        txt = theme.color("hds-txt", d.txt), txt2 = theme.color("hds-txt-2", d.txt2), txt3 = theme.color("hds-txt-3", d.txt3),
        gold = theme.color("hds-gold", d.gold), gold2 = theme.color("hds-gold-2", d.gold2),
        txtOnGold = theme.color("hds-txt-on-gold", d.txtOnGold),
        violet = theme.color("hds-violet", d.violet), violetDeep = theme.color("hds-violet-deep", d.violetDeep),
        matchGreen = theme.color("hds-match-green", d.matchGreen), matchAmber = theme.color("hds-match-amber", d.matchAmber),
        matchRed = theme.color("hds-match-red", d.matchRed), matchGrey = theme.color("hds-match-grey", d.matchGrey),
        glassBorder = theme.color("hds-glass-border", d.glassBorder),
        discoverAccent = theme.color("hds-discover-accent", d.discoverAccent),
    )
}