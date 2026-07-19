package com.pantheon.android.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)
private val HERO_HEIGHT = 320.dp

// Same fixed-height-backdrop reasoning as the mobile flavor's own
// DetailScreen.kt: bounded to HERO_HEIGHT so its scrim never has to cover
// more than the image itself, and never resizes to chase the header's own
// (generally taller) height.
private val BackdropScrimBrush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.5f to Color.Black.copy(alpha = 0.35f),
    1f to Color.Black.copy(alpha = 0.75f),
)
private val TitleTextShadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 8f)

// TV counterpart of the mobile flavor's DetailScreen.kt — same
// DetailViewModel, fixed-backdrop + sticky-header layering, D-pad-focusable
// androidx.tv.material3 Surfaces instead of touch tiles.
//
// Deliberately NOT using mobile's initial hero-spacer/HERO_OVERLAP partial
// reveal (header starting mostly off-screen, scrolled into view) — that's
// exactly the shape of bug that already burned this screen once (see the
// FocusRequester comment below): tv-foundation's LazyColumn can't auto-
// scroll to reveal an off-screen D-pad focus target, so Play has to be
// reachable in the very first paint. The header here starts at y=0
// immediately instead, fully composed and focusable from frame one, and
// only becomes "sticky" once you've scrolled past it into the episode
// list — same locking behavior, just without a partially-hidden start.
//
// Seasons collapse like EpisodeShelf.tsx on web, but via D-pad *focus*
// (the real D-pad analog of web's hover) rather than mobile's tap-to-toggle
// — moving focus onto a season's header (or, once expanded, one of its
// episode tiles) is what expands it; moving on to a different season's
// header expands that one instead. A collapsed season composes no episode
// tiles at all, so D-pad navigation skips straight from one header to the
// next rather than getting stuck trying to reach something invisible.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    apiClient: ApiClient,
    contentType: String,
    id: String,
    onPlay: (kind: String, id: String, positionMs: Long) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: DetailViewModel = viewModel(factory = DetailViewModel.factory(apiClient, contentType, id))
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val playFocusRequester = remember { FocusRequester() }
    var expandedSeasonNumber by remember { mutableStateOf<Int?>(null) }
    // Measured once off the sticky header's own Column below — needed to
    // keep D-pad focus landing *below* it rather than centered underneath
    // it (real feedback: "Seasons and episodes when focused...appear in the
    // middle of the screen, under the details content instead of below
    // it"). Compose's default focus-triggered scroll only knows the
    // LazyColumn's own viewport (the full screen), not that the sticky
    // header visually covers the top of it, so left alone it centers the
    // newly focused item somewhere in that full viewport — including
    // straight under the header.
    var headerHeightPx by remember { mutableStateOf(0) }

    fun goPlay() {
        scope.launch {
            val target = viewModel.resolvePlayTarget()
            if (target != null) onPlay(target.kind, target.id, target.positionMs)
        }
    }

    // Scrolls so season `index`'s block starts just below the sticky
    // header instead of wherever Compose's default bring-into-view would
    // otherwise land it. A negative scrollOffset pushes the target *down*
    // (LazyListState's own convention: positive scrolls it up/off toward
    // the start) by the header's height, clearing it.
    fun scrollBelowHeader(index: Int) {
        scope.launch { listState.animateScrollToItem(index, scrollOffset = -headerHeightPx) }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        if (viewModel.loading) {
            CircularProgressIndicator(color = GoldColor, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        viewModel.errorMessage?.let { message ->
            Text(message, color = TextDim, modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        // Only after the Play button is actually composed (hasZone gates
        // whether it exists at all) — requestFocus() on an unattached
        // FocusRequester throws.
        if (viewModel.hasZone("play-button")) {
            LaunchedEffect(Unit) { playFocusRequester.requestFocus() }
        }

        if (viewModel.hasZone("hero-backdrop")) {
            Box(modifier = Modifier.fillMaxWidth().height(HERO_HEIGHT).align(Alignment.TopStart)) {
                AsyncImage(
                    model = viewModel.art?.let { apiClient.mediaUrl("/api/${if (contentType == "show") "shows" else "movies"}/$id/art") },
                    contentDescription = viewModel.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(TileBg),
                )
                Box(modifier = Modifier.fillMaxSize().background(BackdropScrimBrush))
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            stickyHeader(key = "header") {
                // No background here — deliberately transparent, same
                // reasoning as the mobile flavor's own DetailScreen.kt: once
                // stuck at the top, whatever's actually behind this header's
                // transparent gaps starts as the fixed hero backdrop (which
                // should show through, not get hidden behind it) and only
                // becomes list content once a season block has genuinely
                // scrolled up underneath — at which point *that* block's own
                // opaque background (below) hides it instead.
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .onGloballyPositioned { headerHeightPx = it.size.height },
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 16.dp)) {
                        Box(modifier = Modifier.width(160.dp).aspectRatio(2f / 3f).background(TileBg)) {
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
                                color = Color.White,
                            )

                            if (viewModel.hasZone("meta-block")) {
                                Row(modifier = Modifier.padding(top = 6.dp)) {
                                    viewModel.year?.let { Text("$it  ", color = Color.White) }
                                    viewModel.rating?.let { Text("★ ${"%.1f".format(it)}  ", color = Color.White) }
                                    Text(if (contentType == "show") "series" else "film", color = Color.White)
                                }
                            }

                            if (viewModel.hasZone("play-button")) {
                                Button(
                                    onClick = ::goPlay,
                                    modifier = Modifier.padding(top = 14.dp).focusRequester(playFocusRequester),
                                ) { Text("▶  Play") }
                            }

                            if (viewModel.overview.isNotEmpty()) {
                                Text(viewModel.overview, color = TextDim, modifier = Modifier.padding(top = 10.dp).width(560.dp))
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
                                Text(g, color = TextDim, modifier = Modifier.background(TileBg).padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            }

            if (viewModel.hasZone("episode-shelves") && contentType == "show") {
                itemsIndexed(viewModel.seasons, key = { _, s -> s.number }) { index, season ->
                    val expanded = expandedSeasonNumber == season.number
                    // Opaque — see the sticky header's own comment above:
                    // this is what actually needs to hide behind the header
                    // once it scrolls up underneath it, not the header
                    // itself.
                    Column(modifier = Modifier.fillMaxWidth().background(BgColor).padding(vertical = 6.dp)) {
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

        // 40dp matches the safe-area margin used everywhere else on TV
        // (Home/Library/ProfileSelect's own root padding) — 24dp here was
        // an outlier tight enough to risk sitting in overscan territory on
        // some TVs.
        TvTextButton(text = "← Back", onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(40.dp))
    }
}

@Composable
private fun SeasonHeaderTile(title: String, count: Int, expanded: Boolean, onFocusExpand: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onFocusExpand,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocusExpand() },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color(0xFF2E2F45),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (focused) GoldColor else Color.White, modifier = Modifier.weight(1f))
            Text("$count episode${if (count == 1) "" else "s"}", color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 10.dp))
            Text(if (expanded) "⌄" else "›", color = TextDim, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EpisodeTile(apiClient: ApiClient, episodeId: String, episodeNumber: Int, title: String, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.width(220.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            colors = ClickableSurfaceDefaults.colors(containerColor = TileBg),
        ) {
            AsyncImage(model = apiClient.mediaUrl("/api/episodes/$episodeId/thumb"), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            "E$episodeNumber  $title",
            color = if (focused) GoldColor else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TvTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.4f))) {
        Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
