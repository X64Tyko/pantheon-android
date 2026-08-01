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
import com.pantheon.android.api.dto.WatchTogetherSession
import com.pantheon.android.ui.theme.LocalPantheonColors
import kotlinx.coroutines.launch

// Manifest-driven Home — the mobile-flavor rendering of the same GET
// /api/tv/manifest response hades/src/tv/TvHome.tsx consumes. Rows/zones
// never carry behavior; every action below is dispatched through the same
// closed itemAction vocabulary (TvItemAction) both platforms share.
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
    // at 0 and relying on a later sync correction — mirrors
    // HomePage.tsx's WatchTogetherCard.go().
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
    // (PlayResolution.kt, shared with the TV flavor's HomeScreen.kt).
    fun resolveAndPlay(contentType: String, id: String) {
        scope.launch {
            val target = resolveShelfPlayTarget(apiClient, contentType, id)
            if (target != null) onPlay(target.kind, target.id, target.positionMs)
        }
    }

    // Split into two explicit actions rather than one onClick — mobile tiles
    // now select-then-act (tap once to focus, Play/Details buttons appear,
    // tap the already-selected tile again to play) instead of navigating on
    // the first tap. "Details" always opens Detail; "Play" honors the row's
    // PLAY_LATEST_EPISODE itemAction the same way the old single onClick did.
    // An episode tile (a mixed shelf's per-episode item) has no detail page
    // of its own — both actions jump straight into that episode, same as
    // hades' directPlayPath.
    fun shelfItemDetails(item: HomeMediaItem) {
        if (item.contentType == "episode") { onPlay("episode", item.id, 0); return }
        onOpenDetail(item.contentType, item.id)
    }
    fun shelfItemPlay(row: TvHomeRow, item: HomeMediaItem) {
        if (item.contentType == "episode") { onPlay("episode", item.id, 0); return }
        if (row.itemAction == TvItemAction.PLAY_LATEST_EPISODE) {
            val latest = item.latestEpisode
            if (latest != null) {
                // The show's resume target might not be this specific latest
                // episode (viewer could be behind on an earlier one) — only
                // resume if the show's own watch-state is actually sitting on
                // this episode already; otherwise it's unwatched, start at 0.
                scope.launch {
                    val state = runCatching { apiClient.service.getShowWatchState(item.id) }.getOrNull()
                    onPlay("episode", latest.episodeId, resolveLatestEpisodePosition(state, latest.episodeId))
                }
                return
            }
        }
        resolveAndPlay(item.contentType, item.id)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bg) {
        if (viewModel.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.gold)
            }
            return@Surface
        }
        viewModel.errorMessage?.let { message ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(message, color = colors.txt2)
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
                    // Gated on the manifest actually declaring library zones
                    // (kairos v105) — an instance with none configured has
                    // nowhere for this button to lead, same treatment Guide
                    // already got.
                    if (viewModel.hasLibraryZones) {
                        OutlinedButton(onClick = onNavigateLibrary) { Text("Library") }
                    }
                    if (viewModel.rows.any { it.type == "guide" }) {
                        OutlinedButton(onClick = onNavigateGuide) { Text("📺  Guide") }
                    }
                    Box(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onSwitchProfile) { Text("👤") }
                }
            }
            // Watch Together is a real 'watch-together'-typed row now (kairos
            // v105), rendered through the same generic ordered-row loop as
            // every other row instead of a hardcoded block positioned ahead
            // of it — gated on the row's presence, not just on there
            // happening to be active sessions right now.
            items(viewModel.rows.filter { it.type != "hero" && it.type != "guide" }, key = { it.id }) { row ->
                when {
                    row.type == "watch-together" -> {
                        if (viewModel.watchTogether.isNotEmpty()) {
                            WatchTogetherZone(
                                apiClient = apiClient,
                                items = viewModel.watchTogether,
                                onJoin = ::joinWatchTogether,
                                onClose = ::closeWatchTogether,
                            )
                        }
                    }
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
    val colors = LocalPantheonColors.current
    val item = viewModel.heroItem ?: return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(colors.bg2),
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
                color = colors.txt,
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
    val colors = LocalPantheonColors.current
    // Scoped per shelf, not globally — selecting a tile in one row doesn't
    // affect any other row's own selection.
    var selectedId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.txt,
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
    val colors = LocalPantheonColors.current
    // Episode tile (mixed shelf) — show the parent show's title instead of
    // the episode's own, same convention as ContinueWatchingZone above and
    // hades' mixedToShelfEntry.
    val displayTitle = item.showTitle ?: item.title
    Column(modifier = Modifier.width(120.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.bg3)
                .clickable(onClick = onTap),
        ) {
            AsyncImage(
                model = item.thumbUrl(apiClient),
                contentDescription = displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (selected) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onPlay), color = colors.gold) {
                        Text("▶", color = colors.txtOnGold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                    TextButton(onClick = onDetails) { Text("Details", color = colors.txt) }
                }
            }
        }
        Text(
            displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.txt,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
        Text(
            "Continue Watching",
            style = MaterialTheme.typography.titleMedium,
            color = colors.txt,
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
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(colors.bg3)) {
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
                            Box(modifier = Modifier.fillMaxWidth(progress).height(3.dp).background(colors.gold))
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
    Box(
        modifier = Modifier
            .width(120.dp)
            .aspectRatio(2f / 3f)
            .background(colors.bg3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("Continue in\nLibrary", color = colors.txt2, style = MaterialTheme.typography.labelMedium)
    }
}

// Every currently-open Watch Together session, any account — mirrors
// HomePage.tsx's WatchTogetherShelf. Tapping a card joins it directly
// (touch has no separate hover-to-preview state to reveal a Join button
// first, same "tap the tile" model ContinueWatchingZone already uses).
@Composable
private fun WatchTogetherZone(
    apiClient: ApiClient,
    items: List<WatchTogetherSession>,
    onJoin: (WatchTogetherSession) -> Unit,
    onClose: (WatchTogetherSession) -> Unit,
) {
    val colors = LocalPantheonColors.current
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            "Watch Together",
            style = MaterialTheme.typography.titleMedium,
            color = colors.txt,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
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
    // Host (or anyone, admin-role check not available client-side without
    // extra plumbing — see the sweep's own note) may close their own
    // session directly from the shelf without joining it first.
    val canClose = apiClient.currentUserId == session.hostUserId
    val thumbPath = if (session.contentType == "movie") "/api/movies/${session.contentId}/thumb"
                    else session.showId?.let { "/api/shows/$it/thumb" }
    val displayTitle = if (session.contentType == "episode") session.showTitle ?: session.title else session.title
    val epCode = if (session.contentType == "episode") {
        "S${(session.season ?: 0).toString().padStart(2, '0')}E${(session.episode ?: 0).toString().padStart(2, '0')}"
    } else null

    Column(modifier = Modifier.width(140.dp).clickable(onClick = onJoin)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(colors.bg3)) {
            AsyncImage(
                model = thumbPath?.let { apiClient.mediaUrl(it) },
                contentDescription = displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp))
                    .background(colors.gold.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("LIVE", color = colors.txtOnGold, style = MaterialTheme.typography.labelSmall)
            }
            if (canClose) {
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("✕", color = colors.txt)
                }
            }
        }
        Text(displayTitle, style = MaterialTheme.typography.bodyMedium, color = colors.txt, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        epCode?.let { Text(it, color = colors.txt2, style = MaterialTheme.typography.bodySmall) }
        Text("${session.hostUsername} · ${session.memberCount} watching", color = colors.txt2, style = MaterialTheme.typography.labelSmall)
    }
}

