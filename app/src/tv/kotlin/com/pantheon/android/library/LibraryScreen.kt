package com.pantheon.android.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.home.HomeMediaItem
import com.pantheon.android.home.thumbUrl

// Not file-private: TvFilterPanel.kt (same package) reuses these tokens and
// the TvChip/TvTextButton primitives below.
val BgColor = Color(0xFF1B1C29)
val GoldColor = Color(0xFFE0B84E)
val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)

// TV counterpart of the mobile flavor's LibraryScreen.kt — same
// LibraryViewModel, D-pad-focusable androidx.tv.material3 Surfaces instead
// of clickable Compose modifiers. Search uses a plain compose.material3
// OutlinedTextField (not tv-material, which has no text field) — already the
// established pattern for this app's Connect/Login screens, themed
// correctly because PantheonTheme.kt wraps both MaterialTheme trees.
//
// Filters button opens TvFilterPanel — the same FilterTreeState/FIELD_DEFS
// rule builder as mobile's FilterPanel, but built from inline chip rows
// instead of DropdownMenu popups: nested popup focus scopes are a known
// D-pad trap (see the search field's own onPreviewKeyEvent workaround
// below), so field/operator selection is just another row of TvChips in
// the normal focus-traversal order.
@Composable
fun LibraryScreen(
    apiClient: ApiClient,
    onOpenDetail: (contentType: String, id: String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(apiClient))
    val gridState = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
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
        TvFilterPanel(
            availableFields = viewModel.filterFields,
            tree = viewModel.filterTree,
            libraries = viewModel.libraries,
            selectedLibraryIds = viewModel.selectedLibraryIds,
            onToggleLibrary = viewModel::toggleLibrary,
            fetchValuesFor = viewModel::filterValuesFor,
            onClose = { filtersOpen = false; viewModel.applyFilters() },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvTextButton(text = "← Back", onClick = onBack)
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.padding(start = 16.dp).weight(1f),
                )
                if (viewModel.filterFields.isNotEmpty() || viewModel.libraries.isNotEmpty()) {
                    TvTextButton(
                        text = if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters",
                        onClick = { filtersOpen = true },
                    )
                }
            }

            if (viewModel.hasZone("search-bar")) {
                // A plain Material3 TextField swallows DPAD up/down as cursor
                // movement once focused, so D-pad navigation dead-ends there
                // with no way to continue to the rest of the screen — a real
                // TV-only trap not present on touch/mouse input. Intercept
                // those two keys ahead of the field's own handling and drive
                // normal Compose focus traversal instead; everything else
                // (typing, left/right cursor movement, DPAD_CENTER to edit)
                // still reaches the field untouched.
                OutlinedTextField(
                    value = viewModel.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { androidx.compose.material3.Text("Search library…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> { focusManager.moveFocus(FocusDirection.Down); true }
                                Key.DirectionUp -> { focusManager.moveFocus(FocusDirection.Up); true }
                                else -> false
                            }
                        },
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (viewModel.loading) {
                    CircularProgressIndicator(color = GoldColor, modifier = Modifier.align(Alignment.Center))
                } else {
                    val items: List<HomeMediaItem> = viewModel.shows.map { HomeMediaItem.ShowItem(it) } +
                        viewModel.movies.map { HomeMediaItem.MovieItem(it) }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        state = gridState,
                        contentPadding = PaddingValues(40.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { "${it.contentType}-${it.id}" }) { item ->
                            LibraryTile(apiClient, item, onClick = { onOpenDetail(item.contentType, item.id) })
                        }
                        if (viewModel.loadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
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
fun TvChip(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) GoldColor else Color.Transparent,
            focusedContainerColor = if (active) GoldColor else Color(0xFF2E2F45),
        ),
    ) {
        Text(
            label,
            color = if (active) Color.Black else if (focused) GoldColor else Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun TvTextButton(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent)) {
        Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun LibraryTile(apiClient: ApiClient, item: HomeMediaItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused },
            colors = ClickableSurfaceDefaults.colors(containerColor = TileBg),
        ) {
            AsyncImage(model = item.thumbUrl(apiClient), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            item.title,
            color = if (focused) GoldColor else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        item.year?.let { Text(it.toString(), color = TextDim) }
    }
}
