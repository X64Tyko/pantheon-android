package com.pantheon.android.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.LibraryWithSource
import com.pantheon.android.api.dto.Movie
import com.pantheon.android.api.dto.Show
import com.pantheon.android.api.dto.TvTheme
import com.pantheon.android.api.dto.TvZone
import com.pantheon.android.filter.FilterTreeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

// Parses a manifest theme color's hex ("#RRGGBB" or CSS-order "#RRGGBBAA",
// alpha LAST — see generate-tv-tokens.mjs's toHex()) into a Compose Color.
// Deliberately not android.graphics.Color.parseColor: that expects Android's
// own #AARRGGBB (alpha FIRST) packing, which would silently misread an
// 8-digit CSS hex's bytes as the wrong channels.
private fun parseCssHex(hex: String): Color? = try {
    val h = hex.removePrefix("#")
    when (h.length) {
        6 -> Color(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
        8 -> Color(
            h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16),
            h.substring(4, 6).toInt(16), h.substring(6, 8).toInt(16),
        )
        else -> null
    }
} catch (e: NumberFormatException) { null }

private const val PAGE_SIZE = 48
private const val SEARCH_DEBOUNCE_MS = 300L

// Kotlin counterpart of hades/src/tv/useZoneManifest.ts('library') combined
// with LibraryStore.ts's fetch/loadMore/setQuery/toggleLibrary and its
// FilterTreeStore-backed rule builder — real usage feedback on mobile
// specifically asked for "just like the web version," not the TV flavor's
// scoped-down single-genre-chip stand-in, so this is the real thing: a
// FilterTreeState (rules + one level of groups, real operators) rather than
// a couple of hardcoded chip fields.
//
// *Which* fields the rule builder offers (filterTree's own field dropdown)
// comes from the manifest's filter-pills zone (kairos v82 migration) — the
// same "server owns which fields are filterable, client owns how each
// field's widget/operators work" split every other manifest zone already
// uses, not a hardcoded field list.
//
// Real per-library selection (GET /api/libraries, selectedLibraryIds
// defaulting to "all", library_ids sent only when it's a strict subset —
// mirrors LibraryStore.ts's own searchParams() exactly) rather than the
// generic All/Shows/Movies content-type toggle the TV flavor still uses.
//
// `q` has no separate wire param: ContentService.cpp's /shows and /movies
// only read `filter`, and a bare (unquoted) word in that syntax already means
// "fuzzy free-text match" — see hades/src/api/client.ts's withCombinedFilter,
// which folds q into filter the same way params() below does.
class LibraryViewModel(private val apiClient: ApiClient) : ViewModel() {

    var zones by mutableStateOf<List<TvZone>>(emptyList())
        private set
    fun hasZone(id: String) = zones.any { it.id == id }
    fun zone(id: String) = zones.find { it.id == id }

    var theme by mutableStateOf<TvTheme?>(null)
        private set

    // Resolves a design-token color to a Compose Color, falling back when
    // the manifest hasn't loaded yet, has no theme (fresh checkout before
    // the generator has ever run), or doesn't have this specific token —
    // never a hard failure, the UI always renders something.
    fun themeColor(token: String, fallback: Color): Color =
        theme?.tokens?.colors?.get(token)?.hex?.let(::parseCssHex) ?: fallback

    // The manifest-declared field allowlist for the rule builder's field
    // dropdown — empty until the manifest loads, at which point the filter
    // button/panel becomes meaningful.
    val filterFields: List<String> get() = zone("filter-pills")?.filterFields ?: emptyList()

    // Same manifest-declared-allowlist principle, for the sort picker (kairos
    // v83) — see SortFields.kt's own comment.
    val sortOptions: List<String> get() = zone("filter-pills")?.sortOptions ?: emptyList()

    var shows by mutableStateOf<List<Show>>(emptyList())
        private set
    var movies by mutableStateOf<List<Movie>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var query by mutableStateOf("")
        private set

    var sort by mutableStateOf("recently_added")
        private set
    var sortDir by mutableStateOf("")
        private set
    // Only meaningful while sort == "random" — a fixed seed keeps the
    // shuffled order stable across a fetch + loadMore pagination sequence
    // (SQLite's bare RANDOM() would otherwise reshuffle on every query),
    // mirrors hades' LibraryStore.ts's own randomSeed exactly.
    var randomSeed by mutableStateOf(Random.nextInt(Int.MAX_VALUE))
        private set

    var libraries by mutableStateOf<List<LibraryWithSource>>(emptyList())
        private set
    var selectedLibraryIds by mutableStateOf<Set<String>>(emptySet())
        private set

    val filterTree = FilterTreeState()

    private var page = 0
    private var searchJob: Job? = null
    private val filterValuesCache = mutableMapOf<String, List<String>>()

    init {
        viewModelScope.launch {
            val manifest = runCatching { apiClient.service.getTvManifest() }.getOrNull()
            zones = manifest?.library?.zones?.sortedBy { it.order } ?: emptyList()
            theme = manifest?.theme
            val libs = runCatching { apiClient.service.getLibraries() }.getOrDefault(emptyList())
            libraries = libs
            selectedLibraryIds = libs.map { it.libraryId }.toSet()
            fetch()
        }
    }

    // On-demand, cached per field — mirrors PickerFilters.tsx's
    // fetchFilterValues module-level cache. Called by the field's value
    // picker once that field is actually selected in a rule, not eagerly
    // for every field up front.
    suspend fun filterValuesFor(field: String): List<String> {
        filterValuesCache[field]?.let { return it }
        val values = runCatching { apiClient.service.getFilterValues(field).values.orEmpty() }.getOrDefault(emptyList())
        filterValuesCache[field] = values
        return values
    }

    // Named update*/on*Change rather than set<PropertyName> — the latter
    // collides at the JVM signature level with the mutableStateOf property's
    // own synthesized setter (Kotlin's "platform declaration clash").
    //
    // Called on every keystroke — debounced here (not by the caller) so both
    // flavors' search fields can just forward raw input.
    fun onQueryChange(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            page = 0
            fetch()
        }
    }

    // Named on*Change rather than set<PropertyName> — the latter collides at
    // the JVM signature level with the mutableStateOf property's own
    // synthesized setter (same platform declaration clash onQueryChange's
    // own comment above already calls out).
    fun onSortChange(s: String) {
        sort = s
        page = 0
        viewModelScope.launch { fetch() }
    }

    fun onSortDirChange(d: String) {
        sortDir = d
        page = 0
        viewModelScope.launch { fetch() }
    }

    fun rerollRandom() {
        randomSeed = Random.nextInt(Int.MAX_VALUE)
        if (sort == "random") { page = 0; viewModelScope.launch { fetch() } }
    }

    fun toggleLibrary(libraryId: String) {
        val next = selectedLibraryIds.toMutableSet()
        if (!next.remove(libraryId)) next.add(libraryId)
        selectedLibraryIds = next
        page = 0
        viewModelScope.launch { fetch() }
    }

    fun selectAllLibraries() {
        selectedLibraryIds = libraries.map { it.libraryId }.toSet()
        page = 0
        viewModelScope.launch { fetch() }
    }

    // Called when the filter panel is closed/applied — the panel edits
    // filterTree directly (it's mutable Compose state), this just triggers
    // the actual refetch once the user is done, rather than re-fetching on
    // every keystroke inside the panel.
    fun applyFilters() {
        page = 0
        viewModelScope.launch { fetch() }
    }

    // True once libraries have loaded and every one has been explicitly
    // deselected — distinct from "not loaded yet" (empty set before the
    // first fetch resolves), which should just show the normal loading
    // state instead of an empty-selection message. Mirrors LibraryStore.ts's
    // noLibrariesSelected getter.
    val noLibrariesSelected: Boolean get() = libraries.isNotEmpty() && selectedLibraryIds.isEmpty()

    private fun selectedLibraries() = libraries.filter { it.libraryId in selectedLibraryIds }
    private fun wantsType(type: String): Boolean {
        if (libraries.isEmpty()) return true // not loaded yet — fetch both rather than block on it
        return selectedLibraries().any { it.libraryType == type || it.libraryType == "mixed" }
    }

    // "Recently Aired/Released" is one combined option in the sort picker
    // (shows and movies don't share a single backend sort mode for it), same
    // split as hades' LibraryStore.ts's showSort()/movieSort().
    private fun showSort() = if (sort == "recently_released_or_aired") "recently_aired" else sort
    private fun movieSort() = if (sort == "recently_released_or_aired") "recently_released" else sort

    private fun params(offset: Int, sortValue: String): Map<String, String> {
        val p = mutableMapOf("limit" to PAGE_SIZE.toString(), "offset" to offset.toString(), "hide_empty" to "1")
        val allSelected = libraries.isNotEmpty() && selectedLibraryIds.size >= libraries.size
        if (!allSelected && selectedLibraryIds.isNotEmpty()) p["library_ids"] = selectedLibraryIds.joinToString(",")
        val filter = listOfNotNull(filterTree.toFilterString().takeIf { it.isNotBlank() }, query.trim().takeIf { it.isNotEmpty() })
            .joinToString(" ")
        if (filter.isNotBlank()) p["filter"] = filter
        if (sortValue.isNotBlank()) p["sort"] = sortValue
        if (sortValue == "random") p["seed"] = randomSeed.toString() else if (sortDir.isNotBlank()) p["sort_dir"] = sortDir
        return p
    }

    suspend fun fetch() {
        if (noLibrariesSelected) {
            shows = emptyList(); movies = emptyList(); total = 0; loading = false; errorMessage = null
            return
        }
        loading = true
        errorMessage = null
        try {
            val showRes = if (wantsType("show")) apiClient.service.getShows(params(0, showSort())) else null
            val movieRes = if (wantsType("movie")) apiClient.service.getMovies(params(0, movieSort())) else null
            shows = showRes?.items.orEmpty()
            movies = movieRes?.items.orEmpty()
            total = (showRes?.total ?: 0) + (movieRes?.total ?: 0)
        } catch (e: Exception) {
            shows = emptyList(); movies = emptyList(); total = 0
            errorMessage = "Couldn't load Library: ${e.message ?: "unknown error"}"
        } finally {
            loading = false
        }
    }

    fun loadMore() {
        if (loading || loadingMore || noLibrariesSelected) return
        if (shows.size + movies.size >= total) return
        viewModelScope.launch {
            loadingMore = true
            val nextPage = page + 1
            try {
                val offset = nextPage * PAGE_SIZE
                val showRes = if (wantsType("show")) apiClient.service.getShows(params(offset, showSort())) else null
                val movieRes = if (wantsType("movie")) apiClient.service.getMovies(params(offset, movieSort())) else null
                shows = shows + showRes?.items.orEmpty()
                movies = movies + movieRes?.items.orEmpty()
                page = nextPage
            } catch (e: Exception) {
                errorMessage = "Couldn't load more: ${e.message ?: "unknown error"}"
            } finally {
                loadingMore = false
            }
        }
    }

    companion object {
        fun factory(apiClient: ApiClient) = viewModelFactory {
            initializer { LibraryViewModel(apiClient) }
        }
    }
}
