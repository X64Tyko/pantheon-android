package com.pantheon.android.guide

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

// Same NOW_DARK/NOW_LIGHT oklch→sRGB conversion as the tv flavor's own
// GuideScreen.kt — see that file's comment.
private val NowDark = Color(0xFF352661)
private val NowLight = Color(0xFF786DBD)

private val COLUMN_WIDTH = 180.dp
private val HEADER_HEIGHT = 72.dp
private const val PX_PER_MIN = 2
private const val THIRTY_MIN_MS = 30 * 60_000L

// Mobile counterpart of hades/src/guide/GuidePage.tsx / the tv flavor's own
// GuideScreen.kt — same real channel×time grid and live preview hero, touch-
// scrollable instead of D-pad-navigated: tapping a program cell selects it
// (updates the preview hero's text + starts that channel's live preview,
// same session machinery as TV) rather than tuning straight into it: a
// second tap on the hero's Watch button (or the channel header) is what
// actually starts full playback, unifying with how TV's own onFocus/onClick
// split already works rather than inventing a separate touch-only model.
@Composable
fun GuideScreen(
    apiClient: ApiClient,
    onWatchChannel: (channelId: String) -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateLibrary: () -> Unit,
) {
    val viewModel: GuideViewModel = viewModel(factory = GuideViewModel.factory(apiClient))

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Same quick-action row style as Home's own (Library/Guide) —
            // Guide is only ever reached from Home today, so this doubles as
            // this screen's own way back.
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onNavigateHome) { Text("🏠 Home") }
                OutlinedButton(onClick = onNavigateLibrary) { Text("Library") }
            }

            if (viewModel.hasZone("preview-panel")) {
                GuidePreviewCard(viewModel, onWatch = onWatchChannel)
            }

            if (viewModel.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GoldColor) }
            } else if (viewModel.errorMessage != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(viewModel.errorMessage!!, color = TextDim) }
            } else if (viewModel.hasZone("time-grid") || viewModel.hasZone("channel-header")) {
                GuideGridSection(apiClient, viewModel, onWatch = onWatchChannel, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun GuidePreviewCard(viewModel: GuideViewModel, onWatch: (String) -> Unit) {
    // Touch has no default D-pad-landed focus to fall back on — select the
    // first channel once the list loads so the preview card always has
    // something to show, the touch equivalent of TV's default initial-focus
    // landing on its first column.
    LaunchedEffect(viewModel.channels) {
        if (viewModel.focusedChannelId == null) viewModel.channels.firstOrNull()?.let { viewModel.selectChannel(it.channelId) }
    }

    Box(modifier = Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp))) {
        PreviewPlayerView(
            manifestUrl = viewModel.previewManifestUrl,
            reloadKey = viewModel.focusedChannelId,
            modifier = Modifier.fillMaxSize(),
        )
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))

        val channel = viewModel.focusedChannel
        val program = viewModel.focusedProgram ?: viewModel.nowProgram
        if (channel != null) {
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ch ${channel.number} · ${channel.name}", color = TextDim, style = MaterialTheme.typography.labelMedium)
                    channel.contentTag?.takeIf { it.isNotBlank() }?.let { tag ->
                        Text(
                            tag, color = Color.White,
                            modifier = Modifier.padding(start = 8.dp).background(TileBg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                val title = if (program?.itemType == "episode") program.showTitle ?: program.title else program?.title ?: "No program info"
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))

                val epLabel = if (program?.itemType == "episode" && program.season != null && program.episodeNum != null) {
                    "S${program.season.toString().padStart(2, '0')}E${program.episodeNum.toString().padStart(2, '0')}"
                } else null
                val isLive = program != null && program.wallClockStartMs <= viewModel.nowMs && viewModel.nowMs < program.wallClockEndMs
                val startsInMs = if (program != null && !isLive && program.wallClockStartMs > viewModel.nowMs) program.wallClockStartMs - viewModel.nowMs else null
                if (epLabel != null || startsInMs != null) {
                    Row(modifier = Modifier.padding(top = 2.dp)) {
                        epLabel?.let { Text(it, color = TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 10.dp)) }
                        startsInMs?.let { Text("Starts in ${formatCountdown(it)}", color = GoldColor, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                TextButton(onClick = { onWatch(channel.channelId) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("▶  Watch", color = GoldColor)
                }
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
        val paddingPx = with(density) { 24.dp.toPx() }
        verticalScroll.scrollTo((nowOffsetPx - paddingPx).toInt().coerceAtLeast(0))
    }

    Column(modifier = modifier.border(1.dp, LineColor, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))) {
        Row(modifier = Modifier.horizontalScroll(horizontalScroll)) {
            viewModel.channels.forEach { ch ->
                ChannelHeaderCell(
                    apiClient, ch,
                    focused = ch.channelId == viewModel.focusedChannelId,
                    onClick = { viewModel.selectChannel(ch.channelId) },
                )
            }
        }

        val totalMinutes = WINDOW_LOOKBACK_MIN + WINDOW_FORWARD_HOURS * 60
        val gridHeight = (totalMinutes * PX_PER_MIN).dp
        val gridWidth = COLUMN_WIDTH * viewModel.channels.size.coerceAtLeast(1)

        Box(modifier = Modifier.fillMaxWidth().fillMaxSize().verticalScroll(verticalScroll).horizontalScroll(horizontalScroll)) {
            Row(
                modifier = Modifier.width(gridWidth).height(gridHeight)
                    .drawBehind {
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
                        onSelectProgram = { program -> viewModel.selectProgram(ch.channelId, program) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelHeaderCell(apiClient: ApiClient, channel: Channel, focused: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.width(COLUMN_WIDTH).height(HEADER_HEIGHT)
            .background(if (focused) TileBgLight else TileBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(channel.number.toString(), color = if (focused) GoldColor else TextDim, style = MaterialTheme.typography.titleSmall)
            if (channel.logoPath != null) {
                AsyncImage(
                    model = apiClient.mediaUrl("/api/channels/${channel.channelId}/logo"),
                    contentDescription = channel.name,
                    modifier = Modifier.height(18.dp).padding(top = 4.dp),
                )
            } else {
                Text(channel.name, color = TextDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).widthIn(max = COLUMN_WIDTH - 12.dp))
            }
        }
    }
}

@Composable
private fun ChannelColumnCell(programs: List<EpgProgram>, windowStartMs: Long, nowMs: Long, onSelectProgram: (EpgProgram) -> Unit) {
    Box(modifier = Modifier.width(COLUMN_WIDTH).fillMaxSize()) {
        programs.forEach { program ->
            ProgramBlockCell(program = program, windowStartMs = windowStartMs, nowMs = nowMs, onClick = { onSelectProgram(program) })
        }
    }
}

@Composable
private fun ProgramBlockCell(program: EpgProgram, windowStartMs: Long, nowMs: Long, onClick: () -> Unit) {
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

    val label = if (program.itemType == "episode" && program.season != null && program.episodeNum != null) {
        "${program.showTitle ?: program.title} · S${program.season.toString().padStart(2, '0')}E${program.episodeNum.toString().padStart(2, '0')}"
    } else program.title

    val background = when {
        isNow -> Brush.verticalGradient(0f to NowDark, nowFraction to NowDark, nowFraction to NowLight, 1f to NowLight)
        isPast -> Brush.verticalGradient(listOf(TileBg.copy(alpha = 0.55f), TileBg.copy(alpha = 0.55f)))
        else -> Brush.verticalGradient(listOf(TileBgLight, TileBgLight))
    }

    BoxWithConstraints(
        modifier = Modifier
            .offset(x = 2.dp, y = topDp)
            .width(COLUMN_WIDTH - 4.dp)
            .height((heightDp - 2.dp).coerceAtLeast(16.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .border(1.dp, if (isNow) VioletColor else LineColor, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
    ) {
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

// The pulsing line marking exactly where "now" falls within the block — see
// the tv flavor's own GuideScreen.kt for the shared design/comment.
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
