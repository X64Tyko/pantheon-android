package com.pantheon.android.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.Channel

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)

// TV counterpart of hades/src/guide/GuidePage.tsx / TvGuideSection.tsx —
// same deliberate simplification as the mobile flavor (a channel list with
// current-program info, not a full multi-hour time grid; see that file's
// comment for why a pixel-positioned free-form grid was scoped out — the
// exact same tv-foundation-lacks-TvLazyGrid limitation that made
// DetailScreen's Play button unreachable would apply far more severely to a
// 2D time grid). What TV *does* get that mobile doesn't: a live muted
// preview panel that follows D-pad focus as it moves through the channel
// list, using GuideViewModel's preview-session machinery — the natural
// D-pad equivalent of a touch "tap to preview" the pixel-grid version never
// really had either.
@Composable
fun GuideScreen(
    apiClient: ApiClient,
    onWatchChannel: (channelId: String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: GuideViewModel = viewModel(factory = GuideViewModel.factory(apiClient))

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(340.dp)) {
                PreviewPlayerView(manifestUrl = viewModel.previewManifestUrl, modifier = Modifier.fillMaxSize())
                TvTextButton(text = "← Back", onClick = onBack, modifier = Modifier.padding(24.dp))

                val focusedChannel = viewModel.channels.find { it.channelId == viewModel.selectedChannelId }
                if (focusedChannel != null) {
                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                        Text("${focusedChannel.number}  ${focusedChannel.name}", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        val nowTitle = viewModel.currentProgramByChannel[focusedChannel.channelId]?.title
                        if (nowTitle != null) Text(nowTitle, color = TextDim)
                    }
                }
            }

            if (viewModel.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GoldColor) }
            } else if (viewModel.errorMessage != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(viewModel.errorMessage!!, color = TextDim) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(viewModel.channels, key = { it.channelId }) { ch ->
                        ChannelRow(
                            apiClient, ch,
                            nowTitle = viewModel.currentProgramByChannel[ch.channelId]?.title,
                            onFocus = { viewModel.selectChannel(ch.channelId) },
                            onClick = { onWatchChannel(ch.channelId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(apiClient: ApiClient, channel: Channel, nowTitle: String?, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { state -> focused = state.isFocused; if (state.isFocused) onFocus() },
        colors = ClickableSurfaceDefaults.colors(containerColor = if (focused) TileBg else Color.Transparent),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(64.dp).aspectRatio(1f).background(TileBg), contentAlignment = Alignment.Center) {
                AsyncImage(model = apiClient.mediaUrl("/api/channels/${channel.channelId}/logo"), contentDescription = channel.name, modifier = Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.padding(start = 20.dp)) {
                Text("${channel.number}  ${channel.name}", color = if (focused) GoldColor else Color.White)
                Text(
                    nowTitle ?: "No program info",
                    color = TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.4f))) {
        Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
