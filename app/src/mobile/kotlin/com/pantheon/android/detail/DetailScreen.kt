package com.pantheon.android.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)

// Mobile counterpart of hades/src/tv/TvLibraryDetail.tsx — same
// detail.zones gating (hero-backdrop/meta-block/genre-chips/play-button/
// episode-shelves), same DetailViewModel shared with the TV flavor. Episode
// grouping is 'season' order only — see DetailViewModel's own comment.
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

    fun goPlay() {
        scope.launch {
            val target = viewModel.resolvePlayTarget()
            if (target != null) onPlay(target.kind, target.id, target.positionMs)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        if (viewModel.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GoldColor) }
            return@Surface
        }
        viewModel.errorMessage?.let { message ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message, color = TextDim) }
            return@Surface
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                    if (viewModel.hasZone("hero-backdrop")) {
                        AsyncImage(
                            model = viewModel.art?.let { apiClient.mediaUrl("/api/${if (contentType == "show") "shows" else "movies"}/$id/art") },
                            contentDescription = viewModel.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(TileBg),
                        )
                    }
                    TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text("← Back", color = Color.White) }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Box(modifier = Modifier.width(110.dp).aspectRatio(2f / 3f).background(TileBg)) {
                        AsyncImage(
                            model = viewModel.thumb?.let { apiClient.mediaUrl("/api/${if (contentType == "show") "shows" else "movies"}/$id/thumb") },
                            contentDescription = viewModel.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        Text(viewModel.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)

                        if (viewModel.hasZone("meta-block")) {
                            Row(modifier = Modifier.padding(top = 4.dp)) {
                                viewModel.year?.let { Text("$it  ", color = TextDim) }
                                viewModel.rating?.let { Text("★ ${"%.1f".format(it)}  ", color = TextDim) }
                                Text(if (contentType == "show") "series" else "film", color = TextDim)
                            }
                        }

                        if (viewModel.hasZone("play-button")) {
                            Button(onClick = ::goPlay, modifier = Modifier.padding(top = 10.dp)) { Text("▶  Play") }
                        }
                    }
                }

                if (viewModel.hasZone("genre-chips") && viewModel.genres.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        items(viewModel.genres, key = { it }) { g ->
                            Text(g, color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.background(TileBg).padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }

                if (viewModel.overview.isNotEmpty()) {
                    Text(viewModel.overview, color = TextDim, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }

            if (viewModel.hasZone("episode-shelves") && contentType == "show") {
                items(viewModel.seasons, key = { it.number }) { season ->
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(season.name, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(season.episodes, key = { it.episodeId }) { ep ->
                                Column(
                                    modifier = Modifier.width(160.dp).clickable { onPlay("episode", ep.episodeId, 0) },
                                ) {
                                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(TileBg)) {
                                        AsyncImage(
                                            model = ep.thumb?.let { apiClient.mediaUrl("/api/episodes/${ep.episodeId}/thumb") },
                                            contentDescription = ep.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    Text(
                                        "E${ep.episode}  ${ep.title}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
