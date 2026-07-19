package com.pantheon.android.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.Border
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
// hds-violet's own baked-in value, only used if the manifest has no theme
// yet (fresh checkout before the token generator has ever run) — the real
// value normally comes from LibraryViewModel.themeColor("hds-violet", ...).
private val VioletFallback = Color(0xFF9991EB)

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

    // Hoisted out of the search-bar zone block below (rather than local to
    // it) so the BackHandler beneath it can reach them too.
    var searchEditing by remember { mutableStateOf(false) }
    var searchButtonFocused by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val searchHasFocus = searchEditing || searchButtonFocused

    // First Back press while browsing anywhere else on this screen (a grid
    // tile, the Filters button, ...) snaps D-pad focus to the search bar
    // instead of immediately leaving Library — real feedback: "Pressing the
    // back button while browsing the library should snap to the search bar
    // before going back through menus." Only once focus is already on the
    // search bar does a further Back fall through to actual navigation
    // (this handler disables itself, letting the NavHost's own back
    // handling take over). Doesn't fight the search field's own Back
    // handling while actively editing (Key.Back there collapses edit mode
    // via onPreviewKeyEvent, consumed before it ever reaches this
    // dispatcher-level handler) or TvFilterPanel's Back-to-close (its
    // Dialog owns a separate window/back-dispatcher scope entirely).
    BackHandler(enabled = viewModel.hasZone("search-bar") && !searchHasFocus) {
        searchFocusRequester.requestFocus()
    }

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
            sortOptions = viewModel.sortOptions,
            sort = viewModel.sort,
            sortDir = viewModel.sortDir,
            onSetSort = viewModel::onSortChange,
            onSetSortDir = viewModel::onSortDirChange,
            onReroll = viewModel::rerollRandom,
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
                if (viewModel.filterFields.isNotEmpty() || viewModel.libraries.isNotEmpty() || viewModel.sortOptions.isNotEmpty()) {
                    TvTextButton(
                        text = if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters",
                        onClick = { filtersOpen = true },
                    )
                }
            }

            if (viewModel.hasZone("search-bar")) {
                if (searchEditing) {
                    LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
                    // A plain Material3 TextField swallows DPAD up/down as cursor
                    // movement once focused, so D-pad navigation dead-ends there
                    // with no way to continue to the rest of the screen — a real
                    // TV-only trap not present on touch/mouse input. Intercept
                    // those two keys ahead of the field's own handling and drive
                    // normal Compose focus traversal instead; everything else
                    // (typing, left/right cursor movement) still reaches the
                    // field untouched. Back collapses back to the button below
                    // instead of leaving the whole Library screen — the field
                    // losing focus for any reason (Up/Down/Back) collapses it,
                    // one rule instead of three separate special cases.
                    OutlinedTextField(
                        value = viewModel.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { androidx.compose.material3.Text("Search library…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)
                            .focusRequester(searchFocusRequester)
                            .onFocusChanged { if (!it.isFocused) searchEditing = false }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> { focusManager.moveFocus(FocusDirection.Down); true }
                                    Key.DirectionUp -> { focusManager.moveFocus(FocusDirection.Up); true }
                                    Key.Back -> { searchEditing = false; true }
                                    else -> false
                                }
                            },
                    )
                } else {
                    // Search only becomes an actual editable field — and only
                    // then shows the software keyboard — once explicitly
                    // selected (DPAD_CENTER), not merely when D-pad focus
                    // traversal lands here. A plain Compose TextField shows
                    // the keyboard on FOCUS alone, which on a D-pad remote
                    // fires just from navigating past this row on the way to
                    // something else — a real TV-only annoyance touch/mouse
                    // input never has.
                    Surface(
                        onClick = { searchEditing = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)
                            .focusRequester(searchFocusRequester)
                            .onFocusChanged { searchButtonFocused = it.isFocused },
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = TileBg,
                            focusedContainerColor = Color(0xFF2E2F45),
                        ),
                    ) {
                        Text(
                            viewModel.query.ifEmpty { "Search library…" },
                            color = if (viewModel.query.isEmpty()) TextDim else Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
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
                            LibraryTile(
                                apiClient, item,
                                onClick = { onOpenDetail(item.contentType, item.id) },
                                focusBorderColor = viewModel.themeColor("hds-violet", fallback = VioletFallback),
                            )
                        }
                        if (viewModel.loadingMore) {
                            // Explicit key — mixing a keyless trailing item
                            // into a grid whose real items all carry keys is
                            // what let this row's presence toggling (as
                            // loadingMore flips during pagination) desync
                            // the grid's line-index cache from the actual
                            // item list, visually offsetting every tile by a
                            // full row (real feedback: "scrolling the
                            // android library occasionally offsets all
                            // tiles by 3" — exactly one row on this screen's
                            // fixed column count).
                            item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }) {
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
fun TvTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent)) {
        Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun LibraryTile(apiClient: ApiClient, item: HomeMediaItem, onClick: () -> Unit, focusBorderColor: Color) {
    var focused by remember { mutableStateOf(false) }
    Column {
        // Surface's own default focusedScale (1.1x, see androidx.tv.material3
        // ClickableSurfaceDefaults.scale()) visually grows past this Box's
        // laid-out bounds — scale is a paint-time transform, it doesn't
        // reserve extra layout space the way changing the actual size would
        // — which used to paint straight over the title below it. Clipping
        // this outer Box (sized to the fixed aspect ratio, not affected by
        // the inner Surface's own scale) contains that zoom to the tile's
        // own frame: the poster still visibly scales/crops on focus, it just
        // can't bleed into the caption or the next grid row anymore.
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            Surface(
                onClick = onClick,
                modifier = Modifier.fillMaxSize().onFocusChanged { focused = it.isFocused },
                colors = ClickableSurfaceDefaults.colors(containerColor = TileBg),
                // ClickableSurfaceDefaults.border() defaults focusedBorder to
                // Border.None — a focus ring here isn't a platform default,
                // it has to be requested explicitly.
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(2.dp, focusBorderColor), inset = 0.dp, shape = RoundedCornerShape(8.dp)),
                ),
            ) {
                AsyncImage(model = item.thumbUrl(apiClient), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
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
