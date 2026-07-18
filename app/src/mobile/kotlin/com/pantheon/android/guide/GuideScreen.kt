package com.pantheon.android.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.Channel

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)

// Mobile counterpart of hades/src/guide/GuidePage.tsx — deliberately scoped
// down to a channel list (current program per row) rather than a full
// multi-hour time grid: touch has no "hover to preview" concept the way
// D-pad focus does, so there's no natural home for a live preview panel
// here the way there is on TV (see the tv flavor's GuideScreen.kt) — tapping
// a channel just tunes straight into it, the same one-tap model most touch
// live-TV apps use. GuideViewModel's preview-session machinery is unused on
// this flavor for that reason.
@Composable
fun GuideScreen(
    apiClient: ApiClient,
    onWatchChannel: (channelId: String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: GuideViewModel = viewModel(factory = GuideViewModel.factory(apiClient))

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                TextButton(onClick = onBack) { Text("← Back", color = Color.White) }
                Text("Guide", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }

            if (viewModel.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GoldColor) }
            } else if (viewModel.errorMessage != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(viewModel.errorMessage!!, color = TextDim) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(viewModel.channels, key = { it.channelId }) { ch ->
                        ChannelRow(apiClient, ch, viewModel.currentProgramByChannel[ch.channelId]?.title, onClick = { onWatchChannel(ch.channelId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(apiClient: ApiClient, channel: Channel, nowTitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(56.dp).aspectRatio(1f).background(TileBg), contentAlignment = Alignment.Center) {
            AsyncImage(model = apiClient.mediaUrl("/api/channels/${channel.channelId}/logo"), contentDescription = channel.name, modifier = Modifier.fillMaxSize())
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text("${channel.number}  ${channel.name}", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(
                nowTitle ?: "No program info",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
