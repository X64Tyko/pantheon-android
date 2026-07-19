package com.pantheon.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.TvHomeRow
import com.pantheon.android.api.dto.TvItemAction
import com.pantheon.android.api.dto.WatchProgress
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)

// TV counterpart of the mobile flavor's HomeScreen.kt — same HomeViewModel,
// same manifest/action-dispatch logic, different rendering toolkit
// (androidx.tv.material3, D-pad-focus-first). Compose's own LazyRow/Column
// (not a separate "Tv" variant — tv-foundation 1.0.0 doesn't have one) handle
// D-pad focus traversal between focusable children natively.
@Composable
fun HomeScreen(
    apiClient: ApiClient,
    onOpenDetail: (contentType: String, id: String) -> Unit,
    onPlay: (kind: String, id: String, positionMs: Long) -> Unit,
    onNavigateLibrary: () -> Unit,
    onNavigateGuide: () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(apiClient))
    val scope = rememberCoroutineScope()

    fun resolveAndPlay(contentType: String, id: String) {
        if (contentType == "movie") { onPlay("movie", id, 0); return }
        scope.launch {
            val target = runCatching { apiClient.service.getResolvedPlayTarget(id) }.getOrNull()
            if (target != null) onPlay(target.kind, target.id, target.positionMs)
        }
    }

    fun onShelfItemClick(row: TvHomeRow, item: HomeMediaItem) {
        when (row.itemAction) {
            TvItemAction.PLAY_LATEST_EPISODE -> {
                val latest = (item as? HomeMediaItem.ShowItem)?.show?.latestEpisode
                if (latest != null) onPlay("episode", latest.episodeId, 0)
                else onOpenDetail(item.contentType, item.id)
            }
            else -> onOpenDetail(item.contentType, item.id)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        if (viewModel.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = GoldColor)
            return@Box
        }
        viewModel.errorMessage?.let { message ->
            Text(message, color = TextDim, modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                HeroZone(
                    apiClient = apiClient,
                    viewModel = viewModel,
                    onPlay = { item -> resolveAndPlay(item.contentType, item.id) },
                    onViewDetail = { item -> onOpenDetail(item.contentType, item.id) },
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 8.dp)) {
                    FocusableTextButton(text = "Library", onClick = onNavigateLibrary)
                    Box(modifier = Modifier.weight(1f))
                    FocusableTextButton(text = "👤 Switch Profile", onClick = onSwitchProfile)
                }
            }
            items(viewModel.rows.filter { it.type != "hero" }, key = { it.id }) { row ->
                when {
                    row.type == "guide" -> GuideEntryZone(onClick = onNavigateGuide)
                    row.id == "continue-watching" -> {
                        if (viewModel.continueWatching.isNotEmpty()) {
                            ContinueWatchingZone(
                                apiClient = apiClient,
                                items = viewModel.continueWatching,
                                onClick = { cw -> onPlay(cw.contentType, cw.contentId, cw.positionMs) },
                            )
                        }
                    }
                    else -> {
                        val items = viewModel.rowItems[row.id].orEmpty()
                        if (items.isNotEmpty()) {
                            ShelfZone(
                                apiClient = apiClient,
                                title = row.title ?: row.id,
                                items = items,
                                onItemClick = { item -> onShelfItemClick(row, item) },
                                onEndTileClick = if (row.endTile != null) onNavigateLibrary else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroZone(
    apiClient: ApiClient,
    viewModel: HomeViewModel,
    onPlay: (HomeMediaItem) -> Unit,
    onViewDetail: (HomeMediaItem) -> Unit,
) {
    val item = viewModel.heroItem ?: return
    Box(modifier = Modifier.fillMaxWidth().height(420.dp).background(Color(0xFF1F2033))) {
        AsyncImage(
            model = item.artUrl(apiClient),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(40.dp)) {
            Text(item.title, style = MaterialTheme.typography.headlineLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = { onPlay(item) }) { Text("▶  Play") }
                FocusableTextButton(text = "View Details", onClick = { onViewDetail(item) }, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
    LaunchedEffect(viewModel.heroCandidates) {
        while (true) {
            kotlinx.coroutines.delay(9000)
            viewModel.goToHero(viewModel.heroIndex + 1)
        }
    }
}

@Composable
private fun ShelfZone(
    apiClient: ApiClient,
    title: String,
    items: List<HomeMediaItem>,
    onItemClick: (HomeMediaItem) -> Unit,
    onEndTileClick: (() -> Unit)?,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(horizontal = 40.dp, vertical = 6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 40.dp),
        ) {
            items(items, key = { it.id }) { item -> MediaCard(apiClient, item, onClick = { onItemClick(item) }) }
            if (onEndTileClick != null) { item { EndTile(onClick = onEndTileClick) } }
        }
    }
}

@Composable
private fun MediaCard(apiClient: ApiClient, item: HomeMediaItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.width(140.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { focused = it.isFocused },
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF232438)),
        ) {
            AsyncImage(model = item.thumbUrl(apiClient), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) GoldColor else Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        item.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = TextDim) }
    }
}

@Composable
private fun ContinueWatchingZone(apiClient: ApiClient, items: List<WatchProgress>, onClick: (WatchProgress) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Continue Watching", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(horizontal = 40.dp, vertical = 6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 40.dp),
        ) {
            items(items, key = { "${it.contentType}:${it.contentId}" }) { cw ->
                val title = if (cw.contentType == "episode") cw.showTitle ?: cw.title else cw.title
                val progress = if (cw.durationMs > 0) (cw.positionMs.toFloat() / cw.durationMs).coerceIn(0f, 1f) else 0f
                val path = if (cw.contentType == "movie") "/api/movies/${cw.contentId}/thumb" else cw.showId?.let { "/api/shows/$it/thumb" }
                Column(modifier = Modifier.width(140.dp)) {
                    Surface(
                        onClick = { onClick(cw) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF232438)),
                    ) {
                        Box {
                            AsyncImage(model = path?.let { apiClient.mediaUrl(it) }, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                                Box(modifier = Modifier.fillMaxWidth(progress).height(4.dp).background(GoldColor))
                            }
                        }
                    }
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun EndTile(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(140.dp).aspectRatio(2f / 3f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF232438)),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Continue in\nLibrary", color = TextDim, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// Navigation entry point into the real Guide screen, not embedded inline —
// see the mobile flavor's identical GuideEntryZone comment for why.
@Composable
private fun GuideEntryZone(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 40.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text("📺  Live Guide", color = Color.White)
        }
    }
}

// tv-material's Button is styled as a solid filled control (matches the gold
// Play button) — plain text-link-shaped actions (View Details, Library) use
// a focusable Surface instead, matching the ghost/outline button treatment
// hades/src/channel/sharedStyles.module.css's ghostBtn class uses on web.
@Composable
private fun FocusableTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
