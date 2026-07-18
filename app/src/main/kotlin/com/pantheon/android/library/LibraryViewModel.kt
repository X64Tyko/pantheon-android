package com.pantheon.android.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.Movie
import com.pantheon.android.api.dto.Show
import com.pantheon.android.api.dto.TvZone
import com.pantheon.android.util.quoteFilterValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 48
private const val SEARCH_DEBOUNCE_MS = 300L

// Kotlin counterpart of hades/src/tv/useZoneManifest.ts('library') combined
// with LibraryStore.ts's fetch/loadMore/setQuery/setContentType/
// setFilterGenre — scoped down the same deliberate way TvLibrary.tsx already
// is (search + a content-type toggle + one genre chip, not LibraryPage's full
// rule-builder sidebar). library-pills gates the content-type toggle rather
// than driving a real per-library picker — same pragmatic stand-in TvLibrary
// uses, see its own comment.
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
    var contentType by mutableStateOf("all") // "all" | "show" | "movie"
        private set
    var genres by mutableStateOf<List<String>>(emptyList())
        private set
    var filterGenre by mutableStateOf("")
        private set

    private var page = 0
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            zones = runCatching { apiClient.service.getTvManifest().library.zones.sortedBy { it.order } }.getOrDefault(emptyList())
            fetch()
        }
        viewModelScope.launch {
            genres = runCatching { apiClient.service.getFilterValues("genre").values.orEmpty() }.getOrDefault(emptyList())
        }
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

    fun updateContentType(t: String) {
        if (contentType == t) return
        contentType = t
        page = 0
        viewModelScope.launch { fetch() }
    }

    fun updateFilterGenre(g: String) {
        if (filterGenre == g) return
        filterGenre = g
        page = 0
        viewModelScope.launch { fetch() }
    }

    private fun params(offset: Int): Map<String, String> {
        val p = mutableMapOf("limit" to PAGE_SIZE.toString(), "offset" to offset.toString(), "hide_empty" to "1")
        val parts = listOfNotNull(
            filterGenre.takeIf { it.isNotBlank() }?.let { "genre:${quoteFilterValue(it)}" },
            query.trim().takeIf { it.isNotEmpty() },
        )
        if (parts.isNotEmpty()) p["filter"] = parts.joinToString(" ")
        return p
    }

    suspend fun fetch() {
        loading = true
        errorMessage = null
        try {
            val p = params(0)
            val showRes = if (contentType != "movie") apiClient.service.getShows(p) else null
            val movieRes = if (contentType != "show") apiClient.service.getMovies(p) else null
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
        if (loading || loadingMore) return
        if (shows.size + movies.size >= total) return
        viewModelScope.launch {
            loadingMore = true
            val nextPage = page + 1
            try {
                val p = params(nextPage * PAGE_SIZE)
                val showRes = if (contentType != "movie") apiClient.service.getShows(p) else null
                val movieRes = if (contentType != "show") apiClient.service.getMovies(p) else null
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
