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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.MediaLanguages
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)

// Mobile height/overlap for the fixed-backdrop + sticky-header layering
// below — same *behavior* as hades/src/tv/TvLibraryDetail.tsx's
// HERO_HEIGHT_CSS/HERO_OVERLAP (a genuinely fixed backdrop layer that
// shrinks to the locked header's measured height once collapsed, with the
// header itself starting overlapped over the backdrop's bottom edge and
// scrolling up to lock at the top), just resized for a phone screen rather
// than a TV's max(36vh, 320px)/40px.
private val HERO_HEIGHT = 220.dp
private val HERO_OVERLAP = 26.dp

// Header-local scrim (see stickyHeader's own comment on why it needs one
// independent of the backdrop's own gradient) and title text shadow —
// named constants rather than literals inline in the composable, same
// convention as the colors/sizing above.
private val HeaderScrimBrush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.25f),
    0.5f to Color.Black.copy(alpha = 0.55f),
    1f to Color.Black.copy(alpha = 0.7f),
)
private val TitleTextShadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 8f)

// Mobile counterpart of hades/src/tv/TvLibraryDetail.tsx — same
// detail.zones gating (hero-backdrop/meta-block/genre-chips/play-button/
// episode-shelves), same DetailViewModel shared with the TV flavor, and
// deliberately the same scrolling/layering model as the web version (fixed
// backdrop behind a sticky header, per-episode hero retargeting) rather
// than a from-scratch mobile layout — only the sizing is mobile-native.
//
// Web retargets the header's title/overview/backdrop on *hover* (mouse) or
// *focus* (D-pad) — touch has neither, so tapping an episode tile is the
// touch analog of that hover: first tap selects it (updates the header,
// same as hovering would on web), a second tap on the already-selected
// tile — or its own Play circle — actually plays it. This is the same
// select-then-confirm pattern as Home's shelf MediaCard (item 3 feedback),
// applied here to episode tiles per item 8's feedback.
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
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    var selectedEpisodeId by remember { mutableStateOf<String?>(null) }
    val selectedEpisode = remember(selectedEpisodeId, viewModel.seasons) {
        selectedEpisodeId?.let { sid -> viewModel.seasons.flatMap { it.episodes }.find { it.episodeId == sid } }
    }
    var overviewDialogOpen by remember { mutableStateOf(false) }
    var languagesDialogOpen by remember { mutableStateOf(false) }

    fun goPlay() {
        scope.launch {
            val target = viewModel.resolvePlayTarget()
            if (target != null) onPlay(target.kind, target.id, target.positionMs)
        }
    }

    // Same measured-collapse math as useScrollCollapse/useElementHeight on
    // web: the backdrop starts at HERO_HEIGHT, and once the header (a
    // Compose stickyHeader — same locking behavior as CSS position:sticky)
    // has scrolled to the top and locked there, shrinks to exactly the
    // header's own rendered height instead of staying taller than the
    // content it's now just a backdrop plate behind.
    var headerHeightPx by remember { mutableStateOf(0) }
    val heroHeightPx = with(density) { HERO_HEIGHT.toPx() }
    val heroOverlapPx = with(density) { HERO_OVERLAP.toPx() }
    val heroSpacerPx = (heroHeightPx - heroOverlapPx).coerceAtLeast(0f)
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset >= heroSpacerPx }
    }
    val backdropHeight = if (collapsed && headerHeightPx > 0) with(density) { headerHeightPx.toDp() } else HERO_HEIGHT

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        if (viewModel.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = GoldColor) }
            return@Surface
        }
        viewModel.errorMessage?.let { message ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message, color = TextDim) }
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
            // Fixed backdrop layer — never part of the scrolling list, so it
            // can't slide fully off screen the way a plain LazyColumn item
            // would (the item 7 bug). Retargets to the selected episode's
            // still, same as web's backdropUrl.
            if (viewModel.hasZone("hero-backdrop")) {
                Box(modifier = Modifier.fillMaxWidth().height(backdropHeight).align(Alignment.TopStart)) {
                    val backdropUrl = if (selectedEpisode != null) {
                        selectedEpisode.thumb?.let { apiClient.mediaUrl("/api/episodes/${selectedEpisode.episodeId}/thumb") }
                    } else {
                        viewModel.art?.let { apiClient.mediaUrl("/api/${if (contentType == "show") "shows" else "movies"}/$id/art") }
                    }
                    AsyncImage(
                        model = backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(TileBg),
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to BgColor)))
                }
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "hero-spacer") {
                    Spacer(modifier = Modifier.height(with(density) { heroSpacerPx.toDp() }))
                }

                // Locks at the top once scrolled there — the sticky-header
                // primitive is what gives this the same "header overlaps,
                // then locks" behavior as web's position: sticky, no manual
                // offset math needed for the lock itself (only for sizing
                // the backdrop above, which does need it).
                stickyHeader(key = "header") {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .onGloballyPositioned { headerHeightPx = it.size.height },
                    ) {
                        // Header-local scrim — the backdrop's own bottom-fade
                        // gradient is sized to the *unscrolled* hero and
                        // stops being underneath the header at all once
                        // locked/collapsed (the header's own height can
                        // exceed what the backdrop's gradient was tuned
                        // for), so title/meta/overview text was reading
                        // straight off the raw image in that state — this
                        // scrim is part of the header itself, so it holds
                        // regardless of scroll/collapse state or what's
                        // actually in the backdrop image.
                        Box(modifier = Modifier.matchParentSize().background(HeaderScrimBrush))

                        Column(modifier = Modifier.fillMaxWidth()) {
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
                                    Text(
                                        selectedEpisode?.title ?: viewModel.title,
                                        style = MaterialTheme.typography.headlineSmall.copy(shadow = TitleTextShadow),
                                        color = Color.White,
                                    )

                                    if (viewModel.hasZone("meta-block")) {
                                        Row(modifier = Modifier.padding(top = 4.dp)) {
                                            viewModel.year?.let { Text("$it  ", color = Color.White) }
                                            viewModel.rating?.let { Text("★ ${"%.1f".format(it)}  ", color = Color.White) }
                                            Text(if (contentType == "show") "series" else "film", color = Color.White)
                                        }
                                    }

                                    if (viewModel.hasZone("play-button")) {
                                        androidx.compose.material3.Button(onClick = ::goPlay, modifier = Modifier.padding(top = 10.dp)) { Text("▶  Play") }
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

                            val overviewText = selectedEpisode?.overview ?: viewModel.overview
                            if (overviewText.isNotEmpty()) {
                                var overflowing by remember { mutableStateOf(false) }
                                Text(
                                    overviewText,
                                    color = TextDim,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { result -> overflowing = result.hasVisualOverflow },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                                if (overflowing) {
                                    TextButton(onClick = { overviewDialogOpen = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                                        Text("Read more", color = GoldColor)
                                    }
                                }
                            }

                            // Clamped, same reasoning as the overview above —
                            // a well-tagged anime rip can embed 8-10 dub/
                            // subtitle languages, and hades' own
                            // LanguageChips.tsx shows all of them unclamped
                            // (fine on a desktop-width panel, not on a phone)
                            // — see item 10's own feedback. "+N more" opens
                            // the same full list.
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
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(season.name, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
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
                                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(TileBg)) {
                                            AsyncImage(
                                                model = ep.thumb?.let { apiClient.mediaUrl("/api/episodes/${ep.episodeId}/thumb") },
                                                contentDescription = ep.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            if (isSelected) {
                                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                                                Box(
                                                    modifier = Modifier.align(Alignment.Center).background(GoldColor, RoundedCornerShape(50))
                                                        .clickable { onPlay("episode", ep.episodeId, 0) }
                                                        .padding(10.dp),
                                                ) {
                                                    Text("▶", color = Color.Black)
                                                }
                                            }
                                        }
                                        Text(
                                            "E${ep.episode}  ${ep.title}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) GoldColor else Color.White,
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

            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { Text("← Back", color = Color.White) }
        }
    }
}

@Composable
private fun OverviewDialog(title: String, overview: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(color = BgColor, shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(overview, color = TextDim, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(top = 12.dp)) {
                    Text("Close", color = GoldColor)
                }
            }
        }
    }
}

private const val MAX_VISIBLE_LANGUAGES = 4

@Composable
private fun LanguageRow(label: String, codes: List<String>, onShowAll: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
        codes.take(MAX_VISIBLE_LANGUAGES).forEach { code ->
            Text(
                languageDisplayName(code),
                color = TextDim,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(TileBg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp).padding(end = 6.dp),
            )
        }
        if (codes.size > MAX_VISIBLE_LANGUAGES) {
            Text(
                "+${codes.size - MAX_VISIBLE_LANGUAGES} more",
                color = GoldColor,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = onShowAll).background(TileBg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun LanguagesDialog(languages: MediaLanguages, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(color = BgColor, shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Languages", style = MaterialTheme.typography.titleLarge, color = Color.White)
                languages.audio?.takeIf { it.isNotEmpty() }?.let { codes ->
                    Text("Audio", color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp))
                    Text(codes.joinToString(", ") { languageDisplayName(it) }, color = Color.White, modifier = Modifier.padding(top = 4.dp))
                }
                languages.subtitle?.takeIf { it.isNotEmpty() }?.let { codes ->
                    Text("Subtitles", color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp))
                    Text(codes.joinToString(", ") { languageDisplayName(it) }, color = Color.White, modifier = Modifier.padding(top = 4.dp))
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(top = 16.dp)) {
                    Text("Close", color = GoldColor)
                }
            }
        }
    }
}
