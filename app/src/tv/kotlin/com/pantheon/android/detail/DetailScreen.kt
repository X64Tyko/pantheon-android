package com.pantheon.android.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.ui.theme.LocalPantheonColors
import kotlinx.coroutines.launch

private val HERO_HEIGHT = 320.dp

private val BackdropScrimBrush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.5f to Color.Black.copy(alpha = 0.35f),
    1f to Color.Black.copy(alpha = 0.75f),
)
private val TitleTextShadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 8f)

// Compose's own default focus-triggered bring-into-view scroll can race
// scrollBelowHeader()'s intentional header-offset scroll below and
// occasionally win, leaving a focused season/episode centered under the
// sticky header instead of below it. Suppressing it makes
// scrollBelowHeader() the only thing that ever scrolls this list.
private object NoOpBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

// TV counterpart of the mobile flavor's DetailScreen.kt. No hero-spacer —
// the header (and its backdrop) starts at y=0 immediately so Play is
// reachable in the first paint.
//
// Seasons collapse via D-pad focus rather than mobile's tap-to-toggle.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    apiClient: ApiClient,
    contentType: String,
    id: String,
    onPlay: (kind: String, id: String, positionMs: Long) -> Unit,
    onWatchTogether: (kind: String, id: String, positionMs: Long, wtSessionId: String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: DetailViewModel = viewModel(factory = DetailViewModel.factory(apiClient, contentType, id))
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val colors = LocalPantheonColors.current
    var watchTogetherLoading by remember { mutableStateOf(false) }
    val playFocusRequester = remember { FocusRequester() }
    var expandedSeasonNumber by remember { mutableStateOf<Int?>(null) }
    var overviewDialogOpen by remember { mutableStateOf(false) }
    // Real rendered height of the header+banner box below — keeps
    // D-pad-focused seasons/episodes scrolling to just below it rather than
    // underneath it. seasonIndex + 1 because the sticky header occupies
    // slot 0 in the LazyColumn's flat item list.
    var headerHeightPx by remember { mutableStateOf(0) }

    fun goPlay() {
        scope.launch {
            val target = viewModel.resolvePlayTarget()
            if (target != null) onPlay(target.kind, target.id, target.positionMs)
        }
    }

    fun goPlayFromBeginning() {
        viewModel.playFromBeginningTarget()?.let { onPlay(it.kind, it.id, it.positionMs) }
    }

    fun goWatchTogether() {
        watchTogetherLoading = true
        scope.launch {
            val result = viewModel.createWatchTogether()
            watchTogetherLoading = false
            if (result != null) {
                val (target, sessionId) = result
                onWatchTogether(target.kind, target.id, target.positionMs, sessionId)
            }
        }
    }

    fun scrollBelowHeader(seasonIndex: Int) {
        scope.launch { listState.animateScrollToItem(seasonIndex + 1, scrollOffset = -headerHeightPx) }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        if (viewModel.loading) {
            CircularProgressIndicator(color = colors.gold, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        viewModel.errorMessage?.let { message ->
            Text(message, color = colors.txt2, modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        if (overviewDialogOpen) {
            OverviewDialog(title = viewModel.title, overview = viewModel.overview, onClose = { overviewDialogOpen = false })
        }

        // Only after the Play button is actually composed (hasZone gates
        // whether it exists at all) — requestFocus() on an unattached
        // FocusRequester throws.
        if (viewModel.hasZone("play-button")) {
            LaunchedEffect(Unit) { playFocusRequester.requestFocus() }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides NoOpBringIntoViewSpec) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            stickyHeader(key = "header") {
                // The banner lives here, sized to at least cover the
                // header's own content — not as a separate fixed layer
                // behind the list — so it's always in front of whatever
                // season/episode content scrolls underneath once stuck.
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = HERO_HEIGHT)
                        .onGloballyPositioned { headerHeightPx = it.size.height },
                ) {
                    if (viewModel.hasZone("hero-backdrop")) {
                        val art = viewModel.art
                        if (art != null) {
                            AsyncImage(
                                model = apiClient.mediaUrl("/api/${if (contentType == "show") "shows" else "movies"}/$id/art"),
                                contentDescription = viewModel.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                            Box(modifier = Modifier.matchParentSize().background(BackdropScrimBrush))
                        } else {
                            Box(modifier = Modifier.matchParentSize().background(colors.bg4))
                        }
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 16.dp)) {
                            Box(modifier = Modifier.width(160.dp).aspectRatio(2f / 3f).background(colors.bg3)) {
                                AsyncImage(
                                    model = viewModel.thumb?.let { apiClient.mediaUrl("/api/${if (contentType == "show") "shows" else "movies"}/$id/thumb") },
                                    contentDescription = viewModel.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Column(modifier = Modifier.padding(start = 20.dp)) {
                                Text(
                                    viewModel.title,
                                    style = MaterialTheme.typography.headlineMedium.copy(shadow = TitleTextShadow),
                                    color = colors.txt,
                                )

                                if (viewModel.hasZone("meta-block")) {
                                    Row(modifier = Modifier.padding(top = 6.dp)) {
                                        viewModel.metaFields.forEach { field ->
                                            when (field) {
                                                "year" -> viewModel.year?.let { Text("$it  ", color = colors.txt) }
                                                "rating" -> viewModel.rating?.let { Text("★ ${"%.1f".format(it)}  ", color = colors.txt) }
                                                "content_type" -> Text(if (contentType == "show") "series  " else "film  ", color = colors.txt)
                                            }
                                        }
                                    }
                                }

                                if (viewModel.hasZone("play-button")) {
                                    Row(modifier = Modifier.padding(top = 14.dp)) {
                                        Button(
                                            onClick = ::goPlay,
                                            modifier = Modifier.focusRequester(playFocusRequester),
                                        ) { Text("▶  Play") }
                                        TvTextButton(
                                            text = "Play from Beginning",
                                            onClick = ::goPlayFromBeginning,
                                            modifier = Modifier.padding(start = 12.dp),
                                        )
                                        // Movies and shows only (Kairos's own
                                        // content_type gate on POST
                                        // /api/watch-together).
                                        TvTextButton(
                                            text = if (watchTogetherLoading) "Starting…" else "Watch Together",
                                            onClick = ::goWatchTogether,
                                            modifier = Modifier.padding(start = 12.dp),
                                        )
                                    }
                                }

                                if (viewModel.overview.isNotEmpty()) {
                                    // Capped like mobile's overview text — without this, a long
                                    // synopsis grows the sticky header (backdrop included, via
                                    // matchParentSize) past the screen height, leaving no room
                                    // below it for the season/episode shelf to scroll into.
                                    var overviewOverflowing by remember { mutableStateOf(false) }
                                    Text(
                                        viewModel.overview,
                                        color = colors.txt2,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        onTextLayout = { result -> overviewOverflowing = result.hasVisualOverflow },
                                        modifier = Modifier.padding(top = 10.dp).width(560.dp),
                                    )
                                    if (overviewOverflowing) {
                                        TvTextButton(
                                            text = "More info",
                                            onClick = { overviewDialogOpen = true },
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (viewModel.hasZone("genre-chips") && viewModel.genres.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 40.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            ) {
                                items(viewModel.genres, key = { it }) { g ->
                                    Text(g, color = colors.txt2, modifier = Modifier.background(colors.bg3).padding(horizontal = 12.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (viewModel.hasZone("episode-shelves") && contentType == "show") {
                itemsIndexed(viewModel.seasons, key = { _, s -> s.number }) { index, season ->
                    val expanded = expandedSeasonNumber == season.number
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        SeasonHeaderTile(
                            title = season.name,
                            count = season.episodes.size,
                            expanded = expanded,
                            onFocusExpand = {
                                expandedSeasonNumber = season.number
                                scrollBelowHeader(index)
                            },
                        )
                        if (expanded) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(season.episodes, key = { it.episodeId }) { ep ->
                                    EpisodeTile(
                                        apiClient, episodeId = ep.episodeId, episodeNumber = ep.episode, title = ep.title,
                                        onFocus = {
                                            expandedSeasonNumber = season.number
                                            scrollBelowHeader(index)
                                        },
                                        onClick = { onPlay("episode", ep.episodeId, 0) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        // 40dp matches the safe-area margin used everywhere else on TV
        // (Home/Library/ProfileSelect's own root padding) — 24dp here was
        // an outlier tight enough to risk sitting in overscan territory on
        // some TVs.
        TvTextButton(text = "← Back", onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(40.dp))
    }
}

@Composable
private fun SeasonHeaderTile(title: String, count: Int, expanded: Boolean, onFocusExpand: () -> Unit) {
    val colors = LocalPantheonColors.current
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onFocusExpand,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocusExpand() },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.bg4,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (focused) colors.gold else colors.txt, modifier = Modifier.weight(1f))
            Text("$count episode${if (count == 1) "" else "s"}", color = colors.txt2, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 10.dp))
            Text(if (expanded) "⌄" else "›", color = colors.txt2, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EpisodeTile(apiClient: ApiClient, episodeId: String, episodeNumber: Int, title: String, onFocus: () -> Unit, onClick: () -> Unit) {
    val colors = LocalPantheonColors.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.width(220.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            colors = ClickableSurfaceDefaults.colors(containerColor = colors.bg3),
        ) {
            AsyncImage(model = apiClient.mediaUrl("/api/episodes/$episodeId/thumb"), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            "E$episodeNumber  $title",
            color = if (focused) colors.gold else colors.txt,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TvTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPantheonColors.current
    Surface(onClick = onClick, modifier = modifier, colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.4f))) {
        Text(text, color = colors.txt, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

// TV counterpart of the mobile flavor's OverviewDialog — surfaced from the
// "More info" button so a long synopsis never has to grow the sticky header
// itself (see the overview Text's maxLines cap above).
@Composable
private fun OverviewDialog(title: String, overview: String, onClose: () -> Unit) {
    val colors = LocalPantheonColors.current
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocusRequester.requestFocus() }
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier.width(680.dp).background(colors.bg, RoundedCornerShape(12.dp)).padding(32.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.txt)
            Text(overview, color = colors.txt2, modifier = Modifier.padding(top = 16.dp))
            TvTextButton(
                text = "Close",
                onClick = onClose,
                modifier = Modifier.align(Alignment.End).padding(top = 20.dp).focusRequester(closeFocusRequester),
            )
        }
    }
}
