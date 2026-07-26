package com.pantheon.android.guide

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.Channel
import com.pantheon.android.api.dto.EpgProgram
import com.pantheon.android.ui.theme.LocalPantheonColors

private val COLUMN_WIDTH = 220.dp
// 56dp, not the original 84dp — that was sized for the old stacked
// number-over-logo layout; ChannelHeaderCell now lays the two out
// side-by-side instead (see its own comment), so it doesn't need nearly as
// much height, and the grid below gets that space back.
private val HEADER_HEIGHT = 56.dp
private val PREVIEW_HEIGHT = 260.dp
// How far the channel-header row (and the rest of GuideGridSection's own
// bordered panel, since the header sits at its top) reaches up into the
// preview panel above it — see GuideScreen's own comment for how this is
// achieved (z-order + a shorter top offset, not a manual negative margin).
private val HEADER_OVERLAP = 16.dp
private const val PX_PER_MIN = 2
private const val THIRTY_MIN_MS = 30 * 60_000L

// TV counterpart of hades/src/guide/GuidePage.tsx / TvGuideSection.tsx — a
// real channel×time grid (vertical time axis, horizontal channel columns,
// same PX_PER_MIN/COLUMN_WIDTH layout math) rather than the old flat
// channel-list stand-in. Header row and the scrolling grid body share one
// horizontal ScrollState instance (GuideGridSection below) so they stay in
// lockstep — Compose doesn't need web's manual "two scrollers, one drives
// the other" onScroll handler for this, a shared ScrollState IS the sync.
@Composable
fun GuideScreen(
    apiClient: ApiClient,
    onWatchChannel: (channelId: String) -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateLibrary: () -> Unit,
) {
    val viewModel: GuideViewModel = viewModel(factory = GuideViewModel.factory(apiClient))
    val colors = LocalPantheonColors.current

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        // A Box, not a Column — the grid section (drawn second, so it
        // paints on top) is positioned starting HEADER_OVERLAP above where
        // the preview visually ends, not right after it in normal flow.
        // That's what makes the channel-header row (living at the very top
        // of GuideGridSection's own bordered panel) actually overlap the
        // preview's bottom edge instead of just butting up against it —
        // real z-order + position, not a negative-margin hack — and since
        // the grid section still runs to the bottom of the screen either
        // way, it gains those HEADER_OVERLAP dp as real usable height too.
        if (viewModel.hasZone("preview-panel")) {
            // Home/Library overlay the preview's own top edge instead of
            // sitting in a separate row above it — that row's own thin
            // strip of height was never enough to be useful as anything
            // else, and reclaiming it gives the grid below real room to
            // actually show more than ~1hr at a time.
            GuidePreviewPanel(viewModel, onWatch = onWatchChannel, onNavigateHome = onNavigateHome, onNavigateLibrary = onNavigateLibrary)
        } else {
            // Same quick-action row style as Home's own (Library/Guide),
            // just the reverse pairing — Guide is only ever reached from
            // Home today, so this is both the D-pad affordance and this
            // screen's own hardware-Back target. Only reached when the
            // manifest omits the preview panel entirely — otherwise these
            // buttons live inside GuidePreviewPanel's own overlay.
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 12.dp)) {
                FocusableTextButton(text = "🏠 Home", onClick = onNavigateHome)
                FocusableTextButton(text = "Library", onClick = onNavigateLibrary, modifier = Modifier.padding(start = 12.dp))
            }
        }

        val gridAreaModifier = Modifier.fillMaxSize().padding(top = PREVIEW_HEIGHT - HEADER_OVERLAP, start = 40.dp, end = 40.dp, bottom = 12.dp)
        if (viewModel.loading) {
            Box(gridAreaModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.gold) }
        } else if (viewModel.errorMessage != null) {
            Box(gridAreaModifier, contentAlignment = Alignment.Center) { Text(viewModel.errorMessage!!, color = colors.txt2) }
        } else if (viewModel.hasZone("time-grid") || viewModel.hasZone("channel-header")) {
            GuideGridSection(apiClient, viewModel, onWatch = onWatchChannel, modifier = gridAreaModifier)
        }
    }
}

@Composable
private fun GuidePreviewPanel(viewModel: GuideViewModel, onWatch: (String) -> Unit, onNavigateHome: () -> Unit, onNavigateLibrary: () -> Unit) {
    val colors = LocalPantheonColors.current
    // Default D-pad initial-focus landing isn't guaranteed to land inside
    // the channel grid — the Home/Library buttons overlaid below are
    // composed first — select the first channel explicitly so the preview
    // always has real data from the moment Guide loads, the same safety net
    // the mobile flavor's own GuidePreviewCard already has (touch has no
    // default-focus landing at all to rely on either way).
    LaunchedEffect(viewModel.channels) {
        if (viewModel.focusedChannelId == null) viewModel.channels.firstOrNull()?.let { viewModel.selectChannel(it.channelId) }
    }

    // PREVIEW_HEIGHT — smaller than the 340dp this started at (that left
    // the grid room for barely an hour of programs at once on a real TV).
    // Home/Library now overlay this panel's own top edge rather than
    // sitting in a separate row above it, reclaiming that row's height for
    // the grid; GuideScreen's own grid-section positioning eats another
    // HEADER_OVERLAP dp off the bottom of this panel too (see its comment).
    Box(modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT)) {
        PreviewPlayerView(
            manifestUrl = viewModel.previewManifestUrl,
            reloadKey = viewModel.focusedChannelId,
            modifier = Modifier.fillMaxSize(),
        )
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))

        // Semi-opaque TvTextButton, not the fully-transparent
        // FocusableTextButton Home's own quick-action row uses — this sits
        // over live video, not a solid background, and needs the same
        // legibility treatment the old lone "← Back" overlay had.
        Row(modifier = Modifier.align(Alignment.TopStart).padding(20.dp)) {
            TvTextButton(text = "🏠 Home", onClick = onNavigateHome)
            TvTextButton(text = "Library", onClick = onNavigateLibrary, modifier = Modifier.padding(start = 10.dp))
        }

        val channel = viewModel.focusedChannel
        // The preview hero's TEXT follows whatever program is focused (now
        // or future); the video above always tracks the focused *channel*
        // alone regardless — see GuideViewModel's own comment on why.
        val program = viewModel.focusedProgram ?: viewModel.nowProgram
        if (channel != null) {
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(28.dp).widthIn(max = 640.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ch ${channel.number} · ${channel.name}", color = colors.txt2, style = MaterialTheme.typography.labelLarge)
                    channel.contentTag?.takeIf { it.isNotBlank() }?.let { tag ->
                        Text(
                            tag, color = colors.txt,
                            modifier = Modifier.padding(start = 10.dp).background(colors.bg3, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                val title = if (program?.itemType == "episode") program.showTitle ?: program.title else program?.title ?: "No program info"
                Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.txt, modifier = Modifier.padding(top = 6.dp))

                val epLabel = if (program?.itemType == "episode" && program.season != null && program.episodeNum != null) {
                    "S${program.season.toString().padStart(2, '0')}E${program.episodeNum.toString().padStart(2, '0')}"
                } else null
                val isLive = program != null && program.wallClockStartMs <= viewModel.nowMs && viewModel.nowMs < program.wallClockEndMs
                val startsInMs = if (program != null && !isLive && program.wallClockStartMs > viewModel.nowMs) program.wallClockStartMs - viewModel.nowMs else null
                if (epLabel != null || program?.itemType == "episode" || startsInMs != null) {
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        epLabel?.let { Text(it, color = colors.txt2, modifier = Modifier.padding(end = 12.dp)) }
                        if (program?.itemType == "episode") Text(program.title, color = colors.txt2, modifier = Modifier.padding(end = 12.dp))
                        startsInMs?.let { Text("Starts in ${formatCountdown(it)}", color = colors.gold) }
                    }
                }
                program?.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = colors.txt2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                TvTextButton(text = "▶  Watch", onClick = { onWatch(channel.channelId) }, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

private fun formatCountdown(ms: Long): String {
    val mins = (ms / 60_000).toInt()
    if (mins < 60) return "${mins}m"
    val h = mins / 60
    val m = mins % 60
    return if (m > 0) "${h}h ${m}m" else "${h}h"
}

@Composable
private fun GuideGridSection(apiClient: ApiClient, viewModel: GuideViewModel, onWatch: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPantheonColors.current
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val density = LocalDensity.current
    var scrolledToNow by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.channels) {
        if (scrolledToNow || viewModel.channels.isEmpty()) return@LaunchedEffect
        scrolledToNow = true
        val nowOffsetPx = with(density) { (((viewModel.nowMs - viewModel.windowStartMs) / 60_000f) * PX_PER_MIN).dp.toPx() }
        val paddingPx = with(density) { 40.dp.toPx() }
        verticalScroll.scrollTo((nowOffsetPx - paddingPx).toInt().coerceAtLeast(0))
    }

    Column(modifier = modifier.border(1.dp, colors.glassBorder, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))) {
        Row(modifier = Modifier.horizontalScroll(horizontalScroll)) {
            viewModel.channels.forEach { ch ->
                ChannelHeaderCell(
                    apiClient, ch,
                    focused = ch.channelId == viewModel.focusedChannelId,
                    onFocus = { viewModel.selectChannel(ch.channelId) },
                    onWatch = { onWatch(ch.channelId) },
                )
            }
        }

        val totalMinutes = WINDOW_LOOKBACK_MIN + WINDOW_FORWARD_HOURS * 60
        val gridHeight = (totalMinutes * PX_PER_MIN).dp
        val gridWidth = COLUMN_WIDTH * viewModel.channels.size.coerceAtLeast(1)

        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(verticalScroll).horizontalScroll(horizontalScroll)) {
            Row(
                modifier = Modifier.width(gridWidth).height(gridHeight)
                    .drawBehind {
                        // Background time-grid — a line every 30min. windowStartMs
                        // itself is "now minus a lookback," essentially never
                        // sitting exactly on a real half-hour boundary — phasePx is
                        // how far *into* the current half-hour windowStartMs already
                        // is, negated so the repeating pattern's first line lands on
                        // the true :00/:30 mark instead of an arbitrary y=0 offset.
                        val lineSpacingPx = (30 * PX_PER_MIN).dp.toPx()
                        val phasePx = -(((viewModel.windowStartMs % THIRTY_MIN_MS) / 60_000f) * PX_PER_MIN).dp.toPx()
                        var y = phasePx
                        val strokePx = 1.dp.toPx()
                        while (y < size.height) {
                            if (y >= 0) drawLine(colors.glassBorder, Offset(0f, y), Offset(size.width, y), strokeWidth = strokePx)
                            y += lineSpacingPx
                        }
                    },
            ) {
                viewModel.channels.forEach { ch ->
                    ChannelColumnCell(
                        programs = viewModel.epgByChannel[ch.channelId].orEmpty(),
                        windowStartMs = viewModel.windowStartMs,
                        nowMs = viewModel.nowMs,
                        onFocusProgram = { program -> viewModel.selectProgram(ch.channelId, program) },
                        onWatch = { onWatch(ch.channelId) },
                    )
                }
            }
        }
    }
}

// Number on the left, logo/name filling the remaining height centered to
// its right — was a stacked Column (number above, logo below), which is
// what forced HEADER_HEIGHT as tall as it used to be. Side-by-side needs
// far less vertical room for the same content.
@Composable
private fun ChannelHeaderCell(apiClient: ApiClient, channel: Channel, focused: Boolean, onFocus: () -> Unit, onWatch: () -> Unit) {
    val colors = LocalPantheonColors.current
    Surface(
        onClick = onWatch,
        modifier = Modifier.width(COLUMN_WIDTH).height(HEADER_HEIGHT).onFocusChanged { if (it.isFocused) onFocus() },
        colors = ClickableSurfaceDefaults.colors(containerColor = if (focused) colors.bg4 else colors.bg3),
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(channel.number.toString(), color = if (focused) colors.gold else colors.txt2, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
            Box(modifier = Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) {
                if (channel.logoPath != null) {
                    AsyncImage(
                        model = apiClient.mediaUrl("/api/channels/${channel.channelId}/logo"),
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxHeight().padding(vertical = 6.dp),
                    )
                } else {
                    Text(channel.name, color = colors.txt2, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ChannelColumnCell(
    programs: List<EpgProgram>,
    windowStartMs: Long,
    nowMs: Long,
    onFocusProgram: (EpgProgram) -> Unit,
    onWatch: () -> Unit,
) {
    Box(modifier = Modifier.width(COLUMN_WIDTH).fillMaxSize()) {
        programs.forEach { program ->
            ProgramBlockCell(
                program = program, windowStartMs = windowStartMs, nowMs = nowMs,
                onFocus = { onFocusProgram(program) }, onWatch = onWatch,
            )
        }
    }
}

@Composable
private fun ProgramBlockCell(program: EpgProgram, windowStartMs: Long, nowMs: Long, onFocus: () -> Unit, onWatch: () -> Unit) {
    val colors = LocalPantheonColors.current
    val topMin = (program.wallClockStartMs - windowStartMs) / 60_000f
    val heightMin = (program.wallClockEndMs - program.wallClockStartMs) / 60_000f
    val topDp = (topMin * PX_PER_MIN).dp
    val heightDp = (heightMin * PX_PER_MIN).dp.coerceAtLeast(18.dp)

    val isPast = program.wallClockEndMs <= nowMs
    val isFuture = program.wallClockStartMs > nowMs
    val isNow = !isPast && !isFuture
    val nowFraction = if (isNow) {
        ((nowMs - program.wallClockStartMs).toFloat() / (program.wallClockEndMs - program.wallClockStartMs)).coerceIn(0f, 1f)
    } else 0f

    var focused by remember { mutableStateOf(false) }
    val label = if (program.itemType == "episode" && program.season != null && program.episodeNum != null) {
        "${program.showTitle ?: program.title} · S${program.season.toString().padStart(2, '0')}E${program.episodeNum.toString().padStart(2, '0')}"
    } else program.title

    val background = when {
        isNow -> Brush.verticalGradient(0f to colors.violetDeep, nowFraction to colors.violetDeep, nowFraction to colors.violet, 1f to colors.violet)
        isPast -> Brush.verticalGradient(listOf(colors.bg3.copy(alpha = 0.55f), colors.bg3.copy(alpha = 0.55f)))
        else -> Brush.verticalGradient(listOf(colors.bg4, colors.bg4))
    }

    Surface(
        onClick = onWatch,
        modifier = Modifier
            .offset(x = 2.dp, y = topDp)
            .width(COLUMN_WIDTH - 4.dp)
            .height((heightDp - 2.dp).coerceAtLeast(16.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (isNow) colors.violet else colors.glassBorder)),
            focusedBorder = Border(BorderStroke(1.dp, colors.gold)),
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(background)) {
            if (isNow) {
                NowPulseLine(topOffset = maxHeight * nowFraction)
            }
            Text(
                label,
                color = if (isNow) colors.txt else colors.txt2,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

// The pulsing line marking exactly where "now" falls within the block —
// mirrors hades' hds-guide-now-pulse-anim keyframes. topOffset is the
// block's own height times how far into it "now" falls (0-1), computed by
// the BoxWithConstraints caller since that's the only place the block's
// real rendered height in Dp is known.
@Composable
private fun NowPulseLine(topOffset: Dp) {
    val colors = LocalPantheonColors.current
    val transition = rememberInfiniteTransition(label = "nowPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "nowPulseAlpha",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .offset(y = topOffset - 1.dp)
            .background(colors.violet.copy(alpha = alpha)),
    )
}

@Composable
private fun TvTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPantheonColors.current
    Surface(onClick = onClick, modifier = modifier, colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.4f))) {
        Text(text, color = colors.txt, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

// Same transparent-pill style as HomeScreen.kt's own FocusableTextButton —
// duplicated rather than shared (no shared TV UI layer between screens,
// only the shared ViewModel/DTOs/LocalPantheonColors).
@Composable
private fun FocusableTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPantheonColors.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(text, color = colors.txt, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
