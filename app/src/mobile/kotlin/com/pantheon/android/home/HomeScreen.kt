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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    // Split into two explicit actions rather than one onClick — mobile tiles
    // now select-then-act (tap once to focus, Play/Details buttons appear,
    // tap the already-selected tile again to play) instead of navigating on
    // the first tap. "Details" always opens Detail; "Play" honors the row's
    // PLAY_LATEST_EPISODE itemAction the same way the old single onClick did.
    fun shelfItemDetails(item: HomeMediaItem) = onOpenDetail(item.contentType, item.id)
    fun shelfItemPlay(row: TvHomeRow, item: HomeMediaItem) {
        if (row.itemAction == TvItemAction.PLAY_LATEST_EPISODE) {
            val latest = (item as? HomeMediaItem.ShowItem)?.show?.latestEpisode
            if (latest != null) { onPlay("episode", latest.episodeId, 0); return }
        }
        resolveAndPlay(item.contentType, item.id)
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

        LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                HeroZone(
                    apiClient = apiClient,
                    viewModel = viewModel,
                    onPlay = { item -> resolveAndPlay(item.contentType, item.id) },
                    onViewDetail = { item -> onOpenDetail(item.contentType, item.id) },
                )
            }
            item {
                // Guide sits next to Library rather than as its own
                // manifest-ordered row further down — a quick action, not a
                // shelf, matching hades/src/tv/TvHome.tsx's own
                // quickActionRow (Library+Guide together right after the
                // hero). Still gated on the manifest actually declaring a
                // "guide" row rather than hardcoded, so a manifest that
                // omits Guide entirely still hides the button.
                Row(
                    // statusBarsPadding here is insurance for the loading/
                    // no-hero-row state, where HeroZone renders nothing and
                    // this row becomes the first thing at y=0 — with a hero
                    // present (usual case) it's already well clear of the
                    // status bar, so this is a no-visible-difference safety
                    // net rather than a real layout change in that path.
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onNavigateLibrary) { Text("Library") }
                    if (viewModel.rows.any { it.type == "guide" }) {
                        OutlinedButton(onClick = onNavigateGuide) { Text("📺  Guide") }
                    }
                    Box(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onSwitchProfile) { Text("👤") }
                }
            }
            items(viewModel.rows.filter { it.type != "hero" && it.type != "guide" }, key = { it.id }) { row ->
                when {
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
                                onItemPlay = { item -> shelfItemPlay(row, item) },
                                onItemDetails = { item -> shelfItemDetails(item) },
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
    onItemPlay: (HomeMediaItem) -> Unit,
    onItemDetails: (HomeMediaItem) -> Unit,
    onEndTileClick: (() -> Unit)?,
) {
    // Scoped per shelf, not globally — selecting a tile in one row doesn't
    // affect any other row's own selection.
    var selectedId by remember { mutableStateOf<String?>(null) }

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
            items(items, key = { it.id }) { item ->
                MediaCard(
                    apiClient, item,
                    selected = selectedId == item.id,
                    onTap = {
                        // First tap focuses/selects (reveals Play/Details);
                        // tapping the already-selected tile again plays it —
                        // touch's answer to the D-pad flavor's focus-then-
                        // Enter model, since touch has no separate "hover".
                        if (selectedId == item.id) onItemPlay(item) else selectedId = item.id
                    },
                    onPlay = { onItemPlay(item) },
                    onDetails = { onItemDetails(item) },
                )
            }
            if (onEndTileClick != null) {
                item { EndTile(onClick = onEndTileClick) }
            }
        }
    }
}

@Composable
private fun MediaCard(apiClient: ApiClient, item: HomeMediaItem, selected: Boolean, onTap: () -> Unit, onPlay: () -> Unit, onDetails: () -> Unit) {
    Column(modifier = Modifier.width(120.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF232438))
                .clickable(onClick = onTap),
        ) {
            AsyncImage(
                model = item.thumbUrl(apiClient),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (selected) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onPlay), color = GoldColor) {
                        Text("▶", color = Color.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                    TextButton(onClick = onDetails) { Text("Details", color = Color.White) }
                }
            }
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

