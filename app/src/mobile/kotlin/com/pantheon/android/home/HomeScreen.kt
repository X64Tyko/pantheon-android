package com.pantheon.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.TvHomeRow
import com.pantheon.android.api.dto.TvItemAction
import com.pantheon.android.api.dto.WatchProgress
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)

// Manifest-driven Home — the mobile-flavor rendering of the same GET
// /api/tv/manifest response hades/src/tv/TvHome.tsx consumes. Rows/zones
// never carry behavior; every action below is dispatched through the same
// closed itemAction vocabulary (TvItemAction) both platforms share.
@Composable
fun HomeScreen(
    apiClient: ApiClient,
    onOpenDetail: (contentType: String, id: String) -> Unit,
    onPlay: (kind: String, id: String, positionMs: Long) -> Unit,
    onNavigateLibrary: () -> Unit,
    onNavigateGuide: () -> Unit,
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
                val show = (item as? HomeMediaItem.ShowItem)?.show
                val latest = show?.latestEpisode
                if (latest != null) onPlay("episode", latest.episodeId, 0)
                else onOpenDetail(item.contentType, item.id)
            }
            else -> onOpenDetail(item.contentType, item.id) // TvItemAction.OPEN_DETAIL, the default
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        if (viewModel.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GoldColor)
            }
            return@Surface
        }
        viewModel.errorMessage?.let { message ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(message, color = TextDim)
            }
            return@Surface
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
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = onNavigateLibrary) { Text("Library") }
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(Color(0xFF1F2033)),
    ) {
        AsyncImage(
            model = item.artUrl(apiClient),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.padding(top = 12.dp)) {
                Button(onClick = { onPlay(item) }) { Text("▶  Play") }
                OutlinedButton(onClick = { onViewDetail(item) }, modifier = Modifier.padding(start = 12.dp)) {
                    Text("View Details")
                }
            }
        }
    }
    // Auto-rotate through hero candidates every 9s — same interval TvHome.tsx
    // uses. Restarts if the candidate list itself changes (e.g. after load).
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
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        ) {
            items(items, key = { it.id }) { item -> MediaCard(apiClient, item, onClick = { onItemClick(item) }) }
            if (onEndTileClick != null) {
                item { EndTile(onClick = onEndTileClick) }
            }
        }
    }
}

@Composable
private fun MediaCard(apiClient: ApiClient, item: HomeMediaItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(Color(0xFF232438)),
        ) {
            AsyncImage(
                model = item.thumbUrl(apiClient),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        item.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = TextDim) }
    }
}

@Composable
private fun ContinueWatchingZone(apiClient: ApiClient, items: List<WatchProgress>, onClick: (WatchProgress) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            "Continue Watching",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        ) {
            items(items, key = { "${it.contentType}:${it.contentId}" }) { cw ->
                val title = if (cw.contentType == "episode") cw.showTitle ?: cw.title else cw.title
                val progress = if (cw.durationMs > 0) (cw.positionMs.toFloat() / cw.durationMs).coerceIn(0f, 1f) else 0f
                val path = if (cw.contentType == "movie") "/api/movies/${cw.contentId}/thumb"
                           else cw.showId?.let { "/api/shows/$it/thumb" }
                Column(modifier = Modifier.width(120.dp).clickable { onClick(cw) }) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color(0xFF232438))) {
                        AsyncImage(
                            model = path?.let { apiClient.mediaUrl(it) },
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(Color.Black.copy(alpha = 0.5f)),
                        ) {
                            Box(modifier = Modifier.fillMaxWidth(progress).height(3.dp).background(GoldColor))
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
    Box(
        modifier = Modifier
            .width(120.dp)
            .aspectRatio(2f / 3f)
            .background(Color(0xFF232438))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("Continue in\nLibrary", color = TextDim, style = MaterialTheme.typography.labelMedium)
    }
}

// The Guide zone is deliberately not manifest-rendered content (see project
// plan §1/§6) — it's a bespoke native screen fed by the standard API, not a
// generic zone. Unlike hades/src/tv/TvHome.tsx (which embeds the actual live
// preview/grid inline on Home), this is just a navigation entry point into
// the real Guide screen (guide/GuideScreen.kt) — embedding a second live
// ExoPlayer instance directly in Home's own scroll list would add real
// lifecycle complexity (two concurrent players, teardown on scroll-away)
// for a screen this app doesn't otherwise embed video into at all.
@Composable
private fun GuideEntryZone(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📺  Live Guide", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
