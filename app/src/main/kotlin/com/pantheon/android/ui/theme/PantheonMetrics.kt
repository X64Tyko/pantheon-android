package com.pantheon.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pantheon.android.api.dto.TvTheme

// Same "styling comes from the manifest, not a per-client hardcoded guess"
// principle as PantheonColors, extended to the numeric spacing/radius/
// duration/sizing tokens generate-tv-tokens.mjs already emits from
// hades/src/index.css's own --hds-* custom properties — this data existed on
// the wire from the start (TvManifestService just forwards the whole
// generated file), it just had no Android-side model or consumer until now.
//
// Deliberately position/animation *values*, never position/animation
// *behavior* — this app's own hard-won cross-input (touch/D-pad/KBM) Detail
// screen logic (scrollBelowHeader, focus handoff, etc.) stays fully native
// Compose code. A generic manifest DSL for scroll/focus mechanics would still
// need per-platform hand-interpretation (web's scroll+CSS-transitions,
// Compose's state-driven animation, and eventually Roku's SceneGraph node
// animation/D-pad focus system are different enough engines that the DSL
// itself wouldn't remove the platform-specific work) — sharing the plain
// numbers this mechanics already animates toward gets most of the real
// cross-platform consistency benefit for a fraction of that risk.
data class PantheonMetrics(
    val space1: Dp, val space2: Dp, val space3: Dp, val space4: Dp,
    val space5: Dp, val space6: Dp, val space7: Dp, val spaceTileGap: Dp,
    val radiusXs: Dp, val radiusSm: Dp, val radiusTile: Dp, val radiusMd: Dp,
    val radiusLg: Dp, val radiusPill: Dp, val radiusBtn: Dp, val radiusInput: Dp,
    val tileWidthShelf: Dp, val controlHeight: Dp,
    val transitionFastMs: Int, val transitionMedMs: Int,
    // TV Detail's hero backdrop height floor + sticky-header overlap — a real
    // shared token as of the Detail cross-client consistency pass (see
    // index.css), read here instead of DetailScreen.kt's own previously-
    // independently-hardcoded 320.dp so the two clients can't drift apart on
    // this again.
    val heroHeightTv: Dp, val heroOverlapTv: Dp,
)

// Fallback values — today's real hds-* token values (see index.css), used
// whenever the manifest hasn't loaded yet or predates a given token. Not the
// source of truth; see pantheonMetricsFromTheme for the real manifest-driven
// read. Kept in sync with DefaultPantheonColors' own role/comment.
val DefaultPantheonMetrics = PantheonMetrics(
    space1 = 4.dp, space2 = 6.dp, space3 = 8.dp, space4 = 12.dp,
    space5 = 16.dp, space6 = 20.dp, space7 = 24.dp, spaceTileGap = 10.dp,
    radiusXs = 4.dp, radiusSm = 6.dp, radiusTile = 8.dp, radiusMd = 10.dp,
    radiusLg = 14.dp, radiusPill = 20.dp, radiusBtn = 9.dp, radiusInput = 8.dp,
    tileWidthShelf = 108.dp, controlHeight = 38.dp,
    transitionFastMs = 150, transitionMedMs = 200,
    heroHeightTv = 320.dp, heroOverlapTv = 40.dp,
)

val LocalPantheonMetrics = staticCompositionLocalOf { DefaultPantheonMetrics }

// Resolves every slot above against the manifest's theme.tokens, falling
// back to DefaultPantheonMetrics's matching field wherever a token is
// missing — mirrors pantheonColorsFromTheme exactly.
@Composable
fun pantheonMetricsFromTheme(theme: TvTheme?): PantheonMetrics {
    val d = DefaultPantheonMetrics
    return PantheonMetrics(
        space1 = theme.spacingDp("hds-space-1", d.space1), space2 = theme.spacingDp("hds-space-2", d.space2),
        space3 = theme.spacingDp("hds-space-3", d.space3), space4 = theme.spacingDp("hds-space-4", d.space4),
        space5 = theme.spacingDp("hds-space-5", d.space5), space6 = theme.spacingDp("hds-space-6", d.space6),
        space7 = theme.spacingDp("hds-space-7", d.space7),
        spaceTileGap = theme.spacingDp("hds-space-tile-gap", d.spaceTileGap),
        radiusXs = theme.radiusDp("hds-radius-xs", d.radiusXs), radiusSm = theme.radiusDp("hds-radius-sm", d.radiusSm),
        radiusTile = theme.radiusDp("hds-radius-tile", d.radiusTile), radiusMd = theme.radiusDp("hds-radius-md", d.radiusMd),
        radiusLg = theme.radiusDp("hds-radius-lg", d.radiusLg), radiusPill = theme.radiusDp("hds-radius-pill", d.radiusPill),
        radiusBtn = theme.radiusDp("hds-radius-btn", d.radiusBtn), radiusInput = theme.radiusDp("hds-radius-input", d.radiusInput),
        tileWidthShelf = theme.sizingDp("hds-tile-width-shelf", d.tileWidthShelf),
        controlHeight = theme.sizingDp("hds-control-height", d.controlHeight),
        transitionFastMs = theme.transitionMs("hds-transition-fast", d.transitionFastMs),
        transitionMedMs = theme.transitionMs("hds-transition-med", d.transitionMedMs),
        heroHeightTv = theme.sizingDp("hds-tile-hero-height-tv", d.heroHeightTv),
        heroOverlapTv = theme.sizingDp("hds-tile-hero-overlap-tv", d.heroOverlapTv),
    )
}
