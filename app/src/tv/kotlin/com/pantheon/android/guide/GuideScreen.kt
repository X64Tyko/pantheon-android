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

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)
private val TileBgLight = Color(0xFF2B2C42)
private val LineColor = Color(0xFF34343F)
private val VioletColor = Color(0xFF9991EB)

// The now-block's progress split — dark above where "now" falls within the
// block, lighter below. Real oklch(0.32 0.10 292)/oklch(0.58 0.12 288) →
// sRGB conversion (same purple family as hds-violet, just a wider
// light/dark spread than that single token gives) — mirrors
// hades/src/guide/ChannelColumn.tsx's NOW_DARK/NOW_LIGHT exactly.
private val NowDark = Color(0xFF352661)
private val NowLight = Color(0xFF786DBD)

private val COLUMN_WIDTH = 220.dp
private val HEADER_HEIGHT = 84.dp
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
    onBack: () -> Unit,
) {
    val viewModel: GuideViewModel = viewModel(factory = GuideViewModel.factory(apiClient))

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (viewModel.hasZone("preview-panel")) {
                GuidePreviewPanel(viewModel, onBack, onWatch = onWatchChannel)
            } else {
                TvTextButton(text = "← Back", onClick = onBack, modifier = Modifier.padding(40.dp))
            }

            if (viewModel.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GoldColor) }
            } else if (viewModel.errorMessage != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(viewModel.errorMessage!!, color = TextDim) }
            } else if (viewModel.hasZone("time-grid") || viewModel.hasZone("channel-header")) {
                GuideGridSection(apiClient, viewModel, onWatch = onWatchChannel, modifier = Modifier.weight(1f).padding(horizontal = 40.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun GuidePreviewPanel(viewModel: GuideViewModel, onBack: () -> Unit, onWatch: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(340.dp)) {
        PreviewPlayerView(manifestUrl = viewModel.previewManifestUrl, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        // 40dp matches the safe-area margin used everywhere else on TV —
        // 24dp here was an outlier tight enough to risk sitting in overscan
        // territory on some TVs.
        TvTextButton(text = "← Back", onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(40.dp))

        val channel = viewModel.focusedChannel
        // The preview hero's TEXT follows whatever program is focused (now
        // or future); the video above always tracks the focused *channel*
        // alone regardless — see GuideViewModel's own comment on why.
        val program = viewModel.focusedProgram ?: viewModel.nowProgram
        if (channel != null) {
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(40.dp).widthIn(max = 640.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ch ${channel.number} · ${channel.name}", color = TextDim, style = MaterialTheme.typography.labelLarge)
                    channel.contentTag?.takeIf { it.isNotBlank() }?.let { tag ->
                        Text(
                            tag, color = Color.White,
                            modifier = Modifier.padding(start = 10.dp).background(TileBg, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                val title = if (program?.itemType == "episode") program.showTitle ?: program.title else program?.title ?: "No program info"
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(top = 6.dp))

                val epLabel = if (program?.itemType == "episode" && program.season != null && program.episodeNum != null) {
                    "S${program.season.toString().padStart(2, '0')}E${program.episodeNum.toString().padStart(2, '0')}"
                } else null
                val isLive = program != null && program.wallClockStartMs <= viewModel.nowMs && viewModel.nowMs < program.wallClockEndMs
                val startsInMs = if (program != null && !isLive && program.wallClockStartMs > viewModel.nowMs) program.wallClockStartMs - viewModel.nowMs else null
                if (epLabel != null || program?.itemType == "episode" || startsInMs != null) {
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        epLabel?.let { Text(it, color = TextDim, modifier = Modifier.padding(end = 12.dp)) }
                        if (program?.itemType == "episode") Text(program.title, color = TextDim, modifier = Modifier.padding(end = 12.dp))
                        startsInMs?.let { Text("Starts in ${formatCountdown(it)}", color = GoldColor) }
                    }
                }
                program?.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
                TvTextButton(text = "▶  Watch", onClick = { onWatch(channel.channelId) }, modifier = Modifier.padding(top = 12.dp))
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

    Column(modifier = modifier.border(1.dp, LineColor, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))) {
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
                            if (y >= 0) drawLine(LineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = strokePx)
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

@Composable
private fun ChannelHeaderCell(apiClient: ApiClient, channel: Channel, focused: Boolean, onFocus: () -> Unit, onWatch: () -> Unit) {
    Surface(
        onClick = onWatch,
        modifier = Modifier.width(COLUMN_WIDTH).height(HEADER_HEIGHT).onFocusChanged { if (it.isFocused) onFocus() },
        colors = ClickableSurfaceDefaults.colors(containerColor = if (focused) TileBgLight else TileBg),
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(channel.number.toString(), color = if (focused) GoldColor else TextDim, style = MaterialTheme.typography.titleMedium)
            if (channel.logoPath != null) {
                AsyncImage(
                    model = apiClient.mediaUrl("/api/channels/${channel.channelId}/logo"),
                    contentDescription = channel.name,
                    modifier = Modifier.height(20.dp).padding(top = 4.dp),
                )
            } else {
                Text(channel.name, color = TextDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
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
        isNow -> Brush.verticalGradient(0f to NowDark, nowFraction to NowDark, nowFraction to NowLight, 1f to NowLight)
        isPast -> Brush.verticalGradient(listOf(TileBg.copy(alpha = 0.55f), TileBg.copy(alpha = 0.55f)))
        else -> Brush.verticalGradient(listOf(TileBgLight, TileBgLight))
    }

    Surface(
        onClick = onWatch,
        modifier = Modifier
            .offset(x = 2.dp, y = topDp)
            .width(COLUMN_WIDTH - 4.dp)
            .height((heightDp - 2.dp).coerceAtLeast(16.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (isNow) VioletColor else LineColor)),
            focusedBorder = Border(BorderStroke(1.dp, GoldColor)),
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(background)) {
            if (isNow) {
                NowPulseLine(topOffset = maxHeight * nowFraction)
            }
            Text(
                label,
                color = if (isNow) Color.White else TextDim,
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
            .background(VioletColor.copy(alpha = alpha)),
    )
}

@Composable
private fun TvTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.4f))) {
        Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
