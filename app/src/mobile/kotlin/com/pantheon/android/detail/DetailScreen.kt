package com.pantheon.android.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.MediaLanguages
import com.pantheon.android.ui.theme.LocalPantheonColors
import kotlinx.coroutines.launch

private val HERO_HEIGHT = 220.dp

private val BackdropScrimBrush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.5f to Color.Black.copy(alpha = 0.35f),
    1f to Color.Black.copy(alpha = 0.75f),
)
private val TitleTextShadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 8f)

// Mobile counterpart of hades/src/tv/TvLibraryDetail.tsx. Tapping an
// episode tile is the touch analog of web's hover-to-retarget: first tap
// selects it (updates the header), a second tap (or its Play circle) plays.
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

    var selectedEpisodeId by remember { mutableStateOf<String?>(null) }
    val selectedEpisode = remember(selectedEpisodeId, viewModel.seasons) {
        selectedEpisodeId?.let { sid -> viewModel.seasons.flatMap { it.episodes }.find { it.episodeId == sid } }
    }
    var overviewDialogOpen by remember { mutableStateOf(false) }
    var languagesDialogOpen by remember { mutableStateOf(false) }
    // Touch analog of EpisodeShelf.tsx's hover/focus-driven expand — there's
    // no hover on touch, so each season is an independent tap-to-expand
    // accordion tile instead, collapsed by default (item: "Seasons don't
    // collapse like in the web version").
    var expandedSeasons by remember { mutableStateOf(setOf<Int>()) }

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

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bg) {
        if (viewModel.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.gold) }
            return@Surface
        }
        viewModel.errorMessage?.let { message ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message, color = colors.txt2) }
            return@Surface
        }

        if (overviewDialogOpen) {
            OverviewDialog(
                title = selectedEpisode?.title ?: viewModel.title,
                overview = selectedEpisode?.overview ?: viewModel.overview,
                onClose = { overviewDialogOpen = false },
            )
        }
        if (languagesDialogOpen) {
            viewModel.languages?.let { LanguagesDialog(languages = it, onClose = { languagesDialogOpen = false }) }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                stickyHeader(key = "header") {
                    // Banner lives here only — a single image layer, sized
                    // to at least cover the header's own content, that
                    // starts in normal flow and locks at the top once
                    // scrolled there. No separate fixed layer behind the
                    // list (see mobile counterpart's own history: that used
                    // to double-render this same image as a "peek" behind
                    // the header, mirrored here after TV's own fix).
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = HERO_HEIGHT)) {
                        if (viewModel.hasZone("hero-backdrop")) {
                            val backdropUrl = if (selectedEpisode != null) {
                                selectedEpisode.thumb?.let { apiClient.mediaUrl("/api/episodes/${selectedEpisode.episodeId}/thumb") }
                            } else {
                                viewModel.art?.let { apiClient.mediaUrl("/api/${if (contentType == "show") "shows" else "movies"}/$id/art") }
                            }
                            if (backdropUrl != null) {
                                AsyncImage(
                                    model = backdropUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize(),
                                )
                                Box(modifier = Modifier.matchParentSize().background(BackdropScrimBrush))
                            } else {
                                Box(modifier = Modifier.matchParentSize().background(colors.bg4))
                            }
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                                Box(modifier = Modifier.width(110.dp).aspectRatio(2f / 3f).background(colors.bg3)) {
                                    AsyncImage(
                                        model = viewModel.thumb?.let { apiClient.mediaUrl("/api/${if (contentType == "show") "shows" else "movies"}/$id/thumb") },
                                        contentDescription = viewModel.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Column(modifier = Modifier.padding(start = 14.dp)) {
                                    Text(
                                        selectedEpisode?.title ?: viewModel.title,
                                        style = MaterialTheme.typography.headlineSmall.copy(shadow = TitleTextShadow),
                                        color = colors.txt,
                                    )

                                    if (viewModel.hasZone("meta-block")) {
                                        Row(modifier = Modifier.padding(top = 4.dp)) {
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
                                        // Each button independently gated on the
                                        // zone's `actions` list (kairos v105) —
                                        // see DetailViewModel.hasAction.
                                        if (viewModel.hasAction("play") || viewModel.hasAction("play-from-beginning")) {
                                            Row(modifier = Modifier.padding(top = 10.dp)) {
                                                if (viewModel.hasAction("play")) {
                                                    androidx.compose.material3.Button(onClick = ::goPlay) { Text("▶  Play") }
                                                }
                                                if (viewModel.hasAction("play-from-beginning")) {
                                                    androidx.compose.material3.OutlinedButton(
                                                        onClick = ::goPlayFromBeginning,
                                                        modifier = Modifier.padding(start = 10.dp),
                                                    ) { Text("Play from Beginning") }
                                                }
                                            }
                                        }
                                        // Movies and shows only (Kairos's own
                                        // content_type gate on POST
                                        // /api/watch-together) — never shown for
                                        // a channel since this screen doesn't
                                        // apply to those anyway.
                                        if (viewModel.hasAction("watch-together")) {
                                            androidx.compose.material3.OutlinedButton(
                                                onClick = ::goWatchTogether,
                                                enabled = !watchTogetherLoading,
                                                modifier = Modifier.padding(top = 8.dp),
                                            ) { Text(if (watchTogetherLoading) "Starting…" else "Watch Together") }
                                        }
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
                                        Text(g, color = colors.txt2, style = MaterialTheme.typography.labelMedium, modifier = Modifier.background(colors.bg3).padding(horizontal = 10.dp, vertical = 4.dp))
                                    }
                                }
                            }

                            val overviewText = selectedEpisode?.overview ?: viewModel.overview
                            if (overviewText.isNotEmpty()) {
                                var overflowing by remember { mutableStateOf(false) }
                                Text(
                                    overviewText,
                                    color = colors.txt2,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { result -> overflowing = result.hasVisualOverflow },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                                if (overflowing) {
                                    TextButton(onClick = { overviewDialogOpen = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                                        Text("Read more", color = colors.gold)
                                    }
                                }
                            }

                            val languages = viewModel.languages
                            if (languages != null && (!languages.audio.isNullOrEmpty() || !languages.subtitle.isNullOrEmpty())) {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                                    languages.audio?.takeIf { it.isNotEmpty() }?.let {
                                        LanguageRow(label = "🔊 Audio", codes = it, onShowAll = { languagesDialogOpen = true })
                                    }
                                    languages.subtitle?.takeIf { it.isNotEmpty() }?.let {
                                        LanguageRow(label = "💬 Subtitles", codes = it, onShowAll = { languagesDialogOpen = true })
                                    }
                                }
                            }
                        }
                    }
                }

                if (viewModel.hasZone("episode-shelves") && contentType == "show") {
                    items(viewModel.seasons, key = { it.number }) { season ->
                        val expanded = season.number in expandedSeasons
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        expandedSeasons = if (expanded) expandedSeasons - season.number else expandedSeasons + season.number
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(season.name, style = MaterialTheme.typography.titleMedium, color = colors.txt, modifier = Modifier.weight(1f))
                                Text("${season.episodes.size} episode${if (season.episodes.size == 1) "" else "s"}", color = colors.txt2, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 10.dp))
                                Text(if (expanded) "⌄" else "›", color = colors.txt2, style = MaterialTheme.typography.titleMedium)
                            }
                            if (!expanded) return@Column
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(season.episodes, key = { it.episodeId }) { ep ->
                                    val isSelected = selectedEpisodeId == ep.episodeId
                                    Column(
                                        modifier = Modifier.width(160.dp).clickable {
                                            if (isSelected) onPlay("episode", ep.episodeId, 0) else selectedEpisodeId = ep.episodeId
                                        },
                                    ) {
                                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(colors.bg3)) {
                                            AsyncImage(
                                                model = ep.thumb?.let { apiClient.mediaUrl("/api/episodes/${ep.episodeId}/thumb") },
                                                contentDescription = ep.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            if (isSelected) {
                                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                                                Box(
                                                    modifier = Modifier.align(Alignment.Center).background(colors.gold, RoundedCornerShape(50))
                                                        .clickable { onPlay("episode", ep.episodeId, 0) }
                                                        .padding(10.dp),
                                                ) {
                                                    Text("▶", color = colors.txtOnGold)
                                                }
                                            }
                                        }
                                        Text(
                                            "E${ep.episode}  ${ep.title}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) colors.gold else colors.txt,
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

                // Bottom breathing room — without this the last season's
                // episode titles sit flush against the screen edge (item 6).
                item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(32.dp)) }
            }

            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) { Text("← Back", color = colors.txt) }
        }
    }
}

@Composable
private fun OverviewDialog(title: String, overview: String, onClose: () -> Unit) {
    val colors = LocalPantheonColors.current
    Dialog(onDismissRequest = onClose) {
        Surface(color = colors.bg, shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.txt)
                Text(overview, color = colors.txt2, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(top = 12.dp)) {
                    Text("Close", color = colors.gold)
                }
            }
        }
    }
}

private const val MAX_VISIBLE_LANGUAGES = 4

@Composable
private fun LanguageRow(label: String, codes: List<String>, onShowAll: () -> Unit) {
    val colors = LocalPantheonColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.txt2, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
        codes.take(MAX_VISIBLE_LANGUAGES).forEach { code ->
            Text(
                languageDisplayName(code),
                color = colors.txt2,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(colors.bg3, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp).padding(end = 6.dp),
            )
        }
        if (codes.size > MAX_VISIBLE_LANGUAGES) {
            Text(
                "+${codes.size - MAX_VISIBLE_LANGUAGES} more",
                color = colors.gold,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = onShowAll).background(colors.bg3, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun LanguagesDialog(languages: MediaLanguages, onClose: () -> Unit) {
    val colors = LocalPantheonColors.current
    Dialog(onDismissRequest = onClose) {
        Surface(color = colors.bg, shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Languages", style = MaterialTheme.typography.titleLarge, color = colors.txt)
                languages.audio?.takeIf { it.isNotEmpty() }?.let { codes ->
                    Text("Audio", color = colors.txt2, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp))
                    Text(codes.joinToString(", ") { languageDisplayName(it) }, color = colors.txt, modifier = Modifier.padding(top = 4.dp))
                }
                languages.subtitle?.takeIf { it.isNotEmpty() }?.let { codes ->
                    Text("Subtitles", color = colors.txt2, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp))
                    Text(codes.joinToString(", ") { languageDisplayName(it) }, color = colors.txt, modifier = Modifier.padding(top = 4.dp))
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(top = 16.dp)) {
                    Text("Close", color = colors.gold)
                }
            }
        }
    }
}
