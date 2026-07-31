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
import com.pantheon.android.api.dto.WatchTogetherSession
import com.pantheon.android.ui.theme.LocalPantheonColors
import kotlinx.coroutines.launch

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
    onWatchTogether: (kind: String, id: String, positionMs: Long, wtSessionId: String) -> Unit,
    onNavigateLibrary: () -> Unit,
    onNavigateGuide: () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(apiClient))
    val colors = LocalPantheonColors.current
    val scope = rememberCoroutineScope()

    // join() returns the host's current position along with the usual
    // membership write, so the new VOD session can start there instead of
    // at 0 and relying on a later sync correction — mirrors the mobile
    // flavor's own HomeScreen.kt.
    fun joinWatchTogether(session: WatchTogetherSession) {
        scope.launch {
            val joined = runCatching { apiClient.service.joinWatchTogether(session.sessionId) }.getOrNull() ?: return@launch
            onWatchTogether(session.contentType, session.contentId, joined.positionMs, session.sessionId)
        }
    }

    fun closeWatchTogether(session: WatchTogetherSession) {
        val isHost = apiClient.currentUserId == session.hostUserId
        scope.launch {
            runCatching {
                if (isHost) apiClient.service.closeWatchTogether(session.sessionId) else apiClient.service.leaveWatchTogether(session.sessionId)
            }
            viewModel.removeWatchTogether(session.sessionId)
        }
    }

    // "Play" from any shelf resumes real progress, not just the dedicated
    // Continue Watching row — see resolveShelfPlayTarget's own comment
    // (PlayResolution.kt, shared with the mobile flavor's HomeScreen.kt).
    fun resolveAndPlay(contentType: String, id: String) {
        scope.launch {
            val target = resolveShelfPlayTarget(apiClient, contentType, id)
            if (target != null) onPlay(target.kind, target.id, target.positionMs)
        }
    }

    fun onShelfItemClick(row: TvHomeRow, item: HomeMediaItem) {
        // An episode tile (a mixed shelf's per-episode item) has no detail
        // page of its own — jumps straight into that episode, same as
        // hades' directPlayPath, regardless of the row's own itemAction.
        if (item.contentType == "episode") {
            onPlay("episode", item.id, 0)
            return
        }
        when (row.itemAction) {
            TvItemAction.PLAY_LATEST_EPISODE -> {
                val latest = item.latestEpisode
                if (latest != null) {
                    // The show's resume target might not be this specific
                    // latest episode (viewer could be behind on an earlier
                    // one) — only resume if the show's watch-state is
                    // actually sitting on this episode already.
                    scope.launch {
                        val state = runCatching { apiClient.service.getShowWatchState(item.id) }.getOrNull()
                        onPlay("episode", latest.episodeId, resolveLatestEpisodePosition(state, latest.episodeId))
                    }
                } else onOpenDetail(item.contentType, item.id)
            }
            else -> onOpenDetail(item.contentType, item.id)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        if (viewModel.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = colors.gold)
            return@Box
        }
        viewModel.errorMessage?.let { message ->
            Text(message, color = colors.txt2, modifier = Modifier.align(Alignment.Center))
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
                // Guide sits next to Library in this quick-action row
                // rather than as its own manifest-ordered row further down
                // — matches the mobile flavor's own HomeScreen.kt (real
                // usage feedback: "Guide button needs to be next to
                // library"). Still gated on the manifest actually declaring
                // a "guide" row rather than hardcoded, so a manifest that
                // omits Guide entirely still hides the button.
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 8.dp)) {
                    FocusableTextButton(text = "Library", onClick = onNavigateLibrary)
                    if (viewModel.rows.any { it.type == "guide" }) {
                        FocusableTextButton(text = "📺  Guide", onClick = onNavigateGuide, modifier = Modifier.padding(start = 12.dp))
                    }
                    Box(modifier = Modifier.weight(1f))
                    FocusableTextButton(text = "👤 Switch Profile", onClick = onSwitchProfile)
                }
            }
            if (viewModel.watchTogether.isNotEmpty()) {
                item {
                    WatchTogetherZone(
                        apiClient = apiClient,
                        items = viewModel.watchTogether,
                        onJoin = ::joinWatchTogether,
                        onClose = ::closeWatchTogether,
                    )
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
    val colors = LocalPantheonColors.current
    val item = viewModel.heroItem ?: return
    Box(modifier = Modifier.fillMaxWidth().height(420.dp).background(colors.bg2)) {
        AsyncImage(
            model = item.artUrl(apiClient),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(40.dp)) {
            Text(item.title, style = MaterialTheme.typography.headlineLarge, color = colors.txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val colors = LocalPantheonColors.current
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.txt, modifier = Modifier.padding(horizontal = 40.dp, vertical = 6.dp))
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
    val colors = LocalPantheonColors.current
    var focused by remember { mutableStateOf(false) }
    // Episode tile (mixed shelf) — show the parent show's title instead of
    // the episode's own, same convention as mobile's HomeScreen.kt and
    // hades' mixedToShelfEntry.
    val displayTitle = item.showTitle ?: item.title
    Column(modifier = Modifier.width(140.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { focused = it.isFocused },
            colors = ClickableSurfaceDefaults.colors(containerColor = colors.bg3),
        ) {
            AsyncImage(model = item.thumbUrl(apiClient), contentDescription = displayTitle, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) colors.gold else colors.txt,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val caption = item.episodeCode ?: item.year?.toString()
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.txt2) }
    }
}

@Composable
private fun ContinueWatchingZone(apiClient: ApiClient, items: List<WatchProgress>, onClick: (WatchProgress) -> Unit) {
    val colors = LocalPantheonColors.current
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Continue Watching", style = MaterialTheme.typography.titleMedium, color = colors.txt, modifier = Modifier.padding(horizontal = 40.dp, vertical = 6.dp))
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
                        colors = ClickableSurfaceDefaults.colors(containerColor = colors.bg3),
                    ) {
                        Box {
                            AsyncImage(model = path?.let { apiClient.mediaUrl(it) }, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                                Box(modifier = Modifier.fillMaxWidth(progress).height(4.dp).background(colors.gold))
                            }
                        }
                    }
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.txt, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun EndTile(onClick: () -> Unit) {
    val colors = LocalPantheonColors.current
    Surface(
        onClick = onClick,
        modifier = Modifier.width(140.dp).aspectRatio(2f / 3f),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.bg3),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Continue in\nLibrary", color = colors.txt2, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// tv-material's Button is styled as a solid filled control (matches the gold
// Play button) — plain text-link-shaped actions (View Details, Library) use
// a focusable Surface instead, matching the ghost/outline button treatment
// hades/src/channel/sharedStyles.module.css's ghostBtn class uses on web.
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

// Every currently-open Watch Together session, any account — TV counterpart
// of the mobile flavor's own WatchTogetherZone/Card. D-pad-focusable
// tv.material3 Surface instead of a touch tap target; OK/DPAD_CENTER joins
// directly, matching how every other TV shelf card here already behaves
// (no separate hover-to-reveal-actions state on this platform either).
@Composable
private fun WatchTogetherZone(
    apiClient: ApiClient,
    items: List<WatchTogetherSession>,
    onJoin: (WatchTogetherSession) -> Unit,
    onClose: (WatchTogetherSession) -> Unit,
) {
    val colors = LocalPantheonColors.current
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Watch Together", style = MaterialTheme.typography.titleMedium, color = colors.txt, modifier = Modifier.padding(horizontal = 40.dp, vertical = 6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 40.dp),
        ) {
            items(items, key = { it.sessionId }) { session ->
                WatchTogetherCard(apiClient, session, onJoin = { onJoin(session) }, onClose = { onClose(session) })
            }
        }
    }
}

@Composable
private fun WatchTogetherCard(apiClient: ApiClient, session: WatchTogetherSession, onJoin: () -> Unit, onClose: () -> Unit) {
    val colors = LocalPantheonColors.current
    var focused by remember { mutableStateOf(false) }
    // Host may close their own session directly from the shelf without
    // joining it first — admin-role check not available client-side without
    // extra plumbing, same trimmed scope as the mobile flavor's own card.
    val canClose = apiClient.currentUserId == session.hostUserId
    val thumbPath = if (session.contentType == "movie") "/api/movies/${session.contentId}/thumb"
                    else session.showId?.let { "/api/shows/$it/thumb" }
    val displayTitle = if (session.contentType == "episode") session.showTitle ?: session.title else session.title
    val epCode = if (session.contentType == "episode") {
        "S${(session.season ?: 0).toString().padStart(2, '0')}E${(session.episode ?: 0).toString().padStart(2, '0')}"
    } else null

    Column(modifier = Modifier.width(140.dp)) {
        Surface(
            onClick = onJoin,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused },
            colors = ClickableSurfaceDefaults.colors(containerColor = colors.bg3),
        ) {
            Box {
                AsyncImage(model = thumbPath?.let { apiClient.mediaUrl(it) }, contentDescription = displayTitle, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(colors.gold.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("LIVE", color = colors.txtOnGold, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            displayTitle, style = MaterialTheme.typography.bodyMedium,
            color = if (focused) colors.gold else colors.txt,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        epCode?.let { Text(it, color = colors.txt2, style = MaterialTheme.typography.bodySmall) }
        Text("${session.hostUsername} · ${session.memberCount} watching", color = colors.txt2, style = MaterialTheme.typography.labelSmall)
        if (canClose) {
            FocusableTextButton(text = "✕ Close", onClick = onClose, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
