package com.pantheon.android.detail

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
import androidx.compose.ui.graphics.Color
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

// TV counterpart of the mobile flavor's DetailScreen.kt — same
// DetailViewModel, D-pad-focusable androidx.tv.material3 Surfaces.
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
    // Real on-device bug: this was previously claimed (wrongly — no code
    // backed it) to get initial D-pad focus "by being first in the focus
    // order." It wasn't: the Back button (rendered earlier in composition)
    // took initial focus instead, and plain Compose LazyColumn has no
    // built-in D-pad focus-aware auto-scroll (the same tv-foundation gap
    // noted in HomeScreen.kt), so Play was flat-out unreachable by Down once
    // a long synopsis (movies with no genre chips especially) pushed it
    // below the initial viewport — confirmed via a real accessibility-tree
    // dump showing focus never leaving Back and Play not even composed.
    // Fixed two ways: moved Play above Overview below (guarantees it's
    // within the first-paint viewport regardless of synopsis length) and
    // gave it an explicit initial focus request so entry lands there for
    // real, matching what the old (incorrect) comment claimed.
    val playFocusRequester = remember { FocusRequester() }

    fun goPlay() {
        scope.launch {
            val target = viewModel.resolvePlayTarget()
            if (target != null) onPlay(target.kind, target.id, target.positionMs)
        }
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

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    if (viewModel.hasZone("hero-backdrop")) {
                        AsyncImage(
                            model = viewModel.art?.let { apiClient.mediaUrl("/api/${if (contentType == "show") "shows" else "movies"}/$id/art") },
                            contentDescription = viewModel.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(TileBg),
                        )
                    }
                    TvTextButton(text = "← Back", onClick = onBack, modifier = Modifier.padding(24.dp))
                }

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
                        Text(viewModel.title, style = MaterialTheme.typography.headlineMedium, color = Color.White)

                        if (viewModel.hasZone("meta-block")) {
                            Row(modifier = Modifier.padding(top = 6.dp)) {
                                viewModel.year?.let { Text("$it  ", color = TextDim) }
                                viewModel.rating?.let { Text("★ ${"%.1f".format(it)}  ", color = TextDim) }
                                Text(if (contentType == "show") "series" else "film", color = TextDim)
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

            if (viewModel.hasZone("episode-shelves") && contentType == "show") {
                items(viewModel.seasons, key = { it.number }) { season ->
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(season.name, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(season.episodes, key = { it.episodeId }) { ep ->
                                EpisodeTile(apiClient, episodeId = ep.episodeId, episodeNumber = ep.episode, title = ep.title, onClick = { onPlay("episode", ep.episodeId, 0) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeTile(apiClient: ApiClient, episodeId: String, episodeNumber: Int, title: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.width(220.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused },
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
