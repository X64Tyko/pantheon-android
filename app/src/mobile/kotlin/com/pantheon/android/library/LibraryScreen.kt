package com.pantheon.android.library

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.home.HomeMediaItem
import com.pantheon.android.home.thumbUrl

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)

// Mobile counterpart of hades/src/tv/TvLibrary.tsx — search bar +
// LibraryViewModel shared with the TV flavor, plus a real "Filters" button
// opening FilterPanel's full rule-builder overlay (real usage feedback:
// "just like the web version," field list manifest-driven — see
// LibraryViewModel's own comments). No more inline genre-only chip row.
// Pagination is scroll-position-triggered rather than Paging3 — a real
// PagingSource is the noted future upgrade (see project plan §6), not
// required for this to page correctly: LazyVerticalGrid diffs by key, so
// appending pages doesn't rebuild/corrupt scroll position the way Roku's
// SceneGraph node rebuilding did.
@Composable
fun LibraryScreen(
    apiClient: ApiClient,
    onOpenDetail: (contentType: String, id: String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(apiClient))
    val gridState = rememberLazyGridState()
    var filtersOpen by remember { mutableStateOf(false) }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { lastVisible ->
            val loadedCount = viewModel.shows.size + viewModel.movies.size
            if (lastVisible != null && loadedCount > 0 && lastVisible >= loadedCount - 8) viewModel.loadMore()
        }
    }

    val activeFilterCount = viewModel.filterTree.ruleCount +
        (if (viewModel.libraries.isNotEmpty() && viewModel.selectedLibraryIds.size < viewModel.libraries.size) 1 else 0)

    if (filtersOpen) {
        FilterPanel(
            availableFields = viewModel.filterFields,
            tree = viewModel.filterTree,
            libraries = viewModel.libraries,
            selectedLibraryIds = viewModel.selectedLibraryIds,
            onToggleLibrary = viewModel::toggleLibrary,
            fetchValuesFor = viewModel::filterValuesFor,
            onClose = { filtersOpen = false; viewModel.applyFilters() },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← Back", color = Color.White) }
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                if (viewModel.filterFields.isNotEmpty() || viewModel.libraries.isNotEmpty()) {
                    TextButton(onClick = { filtersOpen = true }) {
                        Text(
                            if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters",
                            color = if (activeFilterCount > 0) GoldColor else Color.White,
                        )
                    }
                }
            }

            if (viewModel.hasZone("search-bar")) {
                OutlinedTextField(
                    value = viewModel.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text("Search library…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).navigationBarsPadding()) {
                if (viewModel.loading) {
                    CircularProgressIndicator(color = GoldColor, modifier = Modifier.align(Alignment.Center))
                } else {
                    val items: List<HomeMediaItem> = viewModel.shows.map { HomeMediaItem.ShowItem(it) } +
                        viewModel.movies.map { HomeMediaItem.MovieItem(it) }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        contentPadding = PaddingValues(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { "${it.contentType}-${it.id}" }) { item ->
                            LibraryTile(apiClient, item, onClick = { onOpenDetail(item.contentType, item.id) })
                        }
                        if (viewModel.loadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = GoldColor)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTile(apiClient: ApiClient, item: HomeMediaItem, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(TileBg)) {
            AsyncImage(
                model = item.thumbUrl(apiClient),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        item.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = TextDim) }
    }
}
