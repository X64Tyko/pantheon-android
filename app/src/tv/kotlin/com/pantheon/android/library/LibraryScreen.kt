package com.pantheon.android.library

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.home.HomeMediaItem
import com.pantheon.android.home.thumbUrl
import com.pantheon.android.home.toShelfTile
import com.pantheon.android.ui.theme.LocalPantheonColors

// TV counterpart of mobile's LibraryScreen.kt: same LibraryViewModel,
// D-pad-focusable tv.material3 Surfaces instead of clickable modifiers.
// Search uses a plain compose.material3 OutlinedTextField (tv-material has
// none) — same pattern as Connect/Login. Filters opens TvFilterPanel, built
// from inline TvChip rows rather than DropdownMenu popups, which are a
// known D-pad focus trap.
@Composable
fun LibraryScreen(
    apiClient: ApiClient,
    onOpenDetail: (contentType: String, id: String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(apiClient))
    val colors = LocalPantheonColors.current
    val gridState = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    var filtersOpen by remember { mutableStateOf(false) }

    // Hoisted so BackHandler below can also reach these.
    var searchEditing by remember { mutableStateOf(false) }
    var searchButtonFocused by remember { mutableStateOf(false) }
    // Outer Surface stays mounted across editing/not-editing; only its
    // content (Text vs. OutlinedTextField) swaps. A single shared
    // FocusRequester used to leave a frame with nothing focused during that
    // swap, and Compose's default focus search filled the gap by landing on
    // "← Back" instead — selecting search silently lost focus.
    val searchOuterFocusRequester = remember { FocusRequester() }
    val searchFieldFocusRequester = remember { FocusRequester() }
    // The field's onFocusChanged fires once, synchronously, on attach with
    // isFocused=false — before the LaunchedEffect's requestFocus() below has
    // run. Unguarded, that false collapsed searchEditing right back (the
    // "flash, then no keyboard" bug). Reset on entry happens in the Surface's
    // onClick, not the LaunchedEffect: the LaunchedEffect runs after the
    // field has already attached and fired its own false, so on repeat
    // entries it would still read the previous session's stale `true`.
    var searchFieldEverFocused by remember { mutableStateOf(false) }
    val searchHasFocus = searchEditing || searchButtonFocused
    // requestFocus() only schedules the move; show(ime) needs focus to have
    // actually landed, so it lives in the field's own onFocusChanged instead.
    val view = LocalView.current
    LaunchedEffect(searchEditing) {
        if (searchEditing) {
            searchFieldFocusRequester.requestFocus()
        } else {
            val window = view.context.findActivity()?.window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
        }
    }
    // Unrouted Up-navigation out of search lands on "← Back" (nearest
    // candidate), not Filters. Routed explicitly to filtersFocusRequester
    // below in both editing and not-editing key handlers.
    val filtersFocusRequester = remember { FocusRequester() }
    val filtersAvailable = viewModel.filterFields.isNotEmpty() || viewModel.libraries.isNotEmpty() || viewModel.sortOptions.isNotEmpty()

    // Claims initial D-pad focus once the search zone exists, matching
    // Detail/TvFilterPanel's own on-entry claims.
    val searchZoneAvailable = viewModel.hasZone("search-bar")
    LaunchedEffect(searchZoneAvailable) {
        if (searchZoneAvailable) searchOuterFocusRequester.requestFocus()
    }

    // First Back press elsewhere on this screen snaps focus to search
    // instead of leaving Library; only Back while already on search falls
    // through to real navigation. Doesn't fight the field's own Back
    // handling (collapses edit mode via onPreviewKeyEvent first) or
    // TvFilterPanel's Back-to-close (separate Dialog back-dispatcher scope).
    BackHandler(enabled = viewModel.hasZone("search-bar") && !searchHasFocus) {
        searchOuterFocusRequester.requestFocus()
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

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvTextButton(text = "← Back", onClick = onBack)
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.txt,
                    modifier = Modifier.padding(start = 16.dp).weight(1f),
                )
                if (filtersAvailable) {
                    TvTextButton(
                        text = if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters",
                        onClick = { filtersOpen = true },
                        modifier = Modifier.focusRequester(filtersFocusRequester),
                    )
                }
            }

            if (viewModel.hasZone("search-bar")) {
                // Search only becomes an actual editable field — and only then
                // shows the software keyboard — once explicitly selected
                // (DPAD_CENTER), not merely when D-pad focus traversal lands
                // here. A plain Compose TextField shows the keyboard on FOCUS
                // alone, which on a D-pad remote fires just from navigating
                // past this row on the way to something else — a real
                // TV-only annoyance touch/mouse input never has. This outer
                // Surface is what stays mounted/focusable the whole time;
                // only its content below swaps between the two states.
                Surface(
                    onClick = { searchFieldEverFocused = false; searchEditing = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)
                        .focusRequester(searchOuterFocusRequester)
                        .onFocusChanged { searchButtonFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                            if (filtersAvailable) filtersFocusRequester.requestFocus()
                            true
                        },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = colors.bg3,
                        focusedContainerColor = colors.bg4,
                    ),
                ) {
                    if (searchEditing) {
                        // A plain Material3 TextField swallows DPAD up/down as
                        // cursor movement once focused, so D-pad navigation
                        // dead-ends there with no way to continue to the rest
                        // of the screen — intercept those two keys ahead of
                        // the field's own handling and drive normal Compose
                        // focus traversal instead; everything else (typing,
                        // left/right cursor movement) still reaches the field
                        // untouched. Losing focus for any reason (Up/Down/
                        // Back, or tapping elsewhere) collapses back to the
                        // button look via the outer Surface's own state.
                        OutlinedTextField(
                            value = viewModel.query,
                            onValueChange = viewModel::onQueryChange,
                            placeholder = { androidx.compose.material3.Text("Search library…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                                .focusRequester(searchFieldFocusRequester)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        searchFieldEverFocused = true
                                        // Real platform focus has now actually landed here (not
                                        // just been requested) — the only point show() is
                                        // guaranteed not to race the window's own focus state.
                                        view.context.findActivity()?.window?.let { window ->
                                            WindowCompat.getInsetsController(window, view)
                                                .show(WindowInsetsCompat.Type.ime())
                                        }
                                    } else if (searchFieldEverFocused) {
                                        // Ignore the spurious isFocused=false this callback
                                        // fires on attach, before the field has ever really
                                        // held focus — see searchFieldEverFocused's own comment
                                        // above. Only a genuine loss of focus collapses back.
                                        searchEditing = false
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionDown -> { focusManager.moveFocus(FocusDirection.Down); true }
                                        // Not moveFocus(Up) — see filtersFocusRequester's own
                                        // comment above for why that lands on "← Back" instead.
                                        Key.DirectionUp -> { if (filtersAvailable) filtersFocusRequester.requestFocus(); true }
                                        Key.Back -> { searchEditing = false; true }
                                        else -> false
                                    }
                                },
                        )
                    } else {
                        Text(
                            viewModel.query.ifEmpty { "Search library…" },
                            color = if (viewModel.query.isEmpty()) colors.txt2 else colors.txt,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (viewModel.loading) {
                    CircularProgressIndicator(color = colors.gold, modifier = Modifier.align(Alignment.Center))
                } else {
                    val items: List<HomeMediaItem> = viewModel.shows.map { it.toShelfTile() } +
                        viewModel.movies.map { it.toShelfTile() }
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
                                focusBorderColor = viewModel.themeColor("hds-violet", fallback = colors.violet),
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
                                    CircularProgressIndicator(color = colors.gold)
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
    val colors = LocalPantheonColors.current
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) colors.gold else Color.Transparent,
            focusedContainerColor = if (active) colors.gold else colors.bg4,
        ),
    ) {
        Text(
            label,
            color = if (active) colors.txtOnGold else if (focused) colors.gold else colors.txt,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun TvTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPantheonColors.current
    Surface(onClick = onClick, modifier = modifier, colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent)) {
        Text(text, color = colors.txt, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun LibraryTile(apiClient: ApiClient, item: HomeMediaItem, onClick: () -> Unit, focusBorderColor: Color) {
    val colors = LocalPantheonColors.current
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
        //
        // The focus border lives here too, not on the inner Surface — a
        // border requested via Surface's own `border` param scales outward
        // together with that same focusedScale zoom (it's part of the
        // Surface's own decoration), which meant it grew straight into this
        // Box's clip region right as it should've become most visible,
        // leaving only a barely-there sliver behind. Drawn on this outer,
        // unscaled Box instead, it stays a crisp, fully visible ring at the
        // tile's true frame regardless of how much the content inside zooms.
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
                .border(2.dp, if (focused) focusBorderColor else Color.Transparent, RoundedCornerShape(8.dp)),
        ) {
            Surface(
                onClick = onClick,
                modifier = Modifier.fillMaxSize().onFocusChanged { focused = it.isFocused },
                colors = ClickableSurfaceDefaults.colors(containerColor = colors.bg3),
            ) {
                AsyncImage(model = item.thumbUrl(apiClient), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            item.title,
            color = if (focused) colors.gold else colors.txt,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        item.year?.let { Text(it.toString(), color = colors.txt2) }
    }
}

// LocalContext.current isn't guaranteed to literally be an Activity (a
// ContextThemeWrapper or similar can sit in between) — walk the wrapper
// chain to find the real one, the same defensive pattern every "get the
// Activity from a Composable" recipe uses. Needed below to reach a real
// Window for WindowCompat.getInsetsController(); there's no way to ask for
// the IME through a bare Context/View pair alone.
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
