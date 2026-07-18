package com.pantheon.android.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.TvHomeRow
import com.pantheon.android.api.dto.WatchProgress
import com.pantheon.android.util.toQueryParams
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Kotlin counterpart of hades/src/tv/useHomeManifest.ts + the manifest-driven
// half of TvHome.tsx's own state. Fetches GET /api/tv/manifest, then resolves
// each shelf row's dataSource into real items via the same two endpoints
// (/api/shows, /api/movies) every platform's manifest consumer calls —
// nothing here is Android-specific business logic, just the same contract
// hades/src/tv/TvHome.tsx implements in TypeScript.
class HomeViewModel(private val apiClient: ApiClient) : ViewModel() {

    var rows by mutableStateOf<List<TvHomeRow>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var rowItems by mutableStateOf<Map<String, List<HomeMediaItem>>>(emptyMap())
        private set
    var continueWatching by mutableStateOf<List<WatchProgress>>(emptyList())
        private set

    // Recently-added shows/movies with backdrop art — the same hero
    // candidate pool TvHome.tsx's heroCandidates computes.
    var heroCandidates by mutableStateOf<List<HomeMediaItem>>(emptyList())
        private set
    var heroIndex by mutableStateOf(0)
        private set

    val heroItem: HomeMediaItem? get() = heroCandidates.getOrNull(heroIndex)

    init {
        load()
    }

    fun goToHero(index: Int) {
        if (heroCandidates.isEmpty()) return
        heroIndex = ((index % heroCandidates.size) + heroCandidates.size) % heroCandidates.size
    }

    private fun load() {
        viewModelScope.launch {
            loading = true
            errorMessage = null
            try {
                val manifest = apiClient.service.getTvManifest()
                rows = manifest.home.rows.sortedBy { it.order }

                val findRow = { id: String -> rows.find { it.id == id } }
                val shelfRowIds = listOf("recent-shows", "recent-movies", "recent-released", "recent-aired")

                fetchShelfRows(shelfRowIds, findRow)

                val cwRow = findRow("continue-watching")
                continueWatching = if (cwRow != null) {
                    runCatching { apiClient.service.getWatchProgress() }.getOrDefault(emptyList())
                } else emptyList()

                val shows = rowItems["recent-shows"].orEmpty()
                val movies = rowItems["recent-movies"].orEmpty()
                heroCandidates = (shows + movies).filter { it.art != null }
                    .ifEmpty { shows }
                heroIndex = 0
            } catch (e: Exception) {
                errorMessage = "Couldn't load Home: ${e.message ?: "unknown error"}"
            } finally {
                loading = false
            }
        }
    }

    private suspend fun fetchShelfRows(rowIds: List<String>, findRow: (String) -> TvHomeRow?) {
        val results = coroutineScope {
            rowIds.map { id ->
                async {
                    val row = findRow(id) ?: return@async id to emptyList()
                    val ds = row.dataSource ?: return@async id to emptyList()
                    val params = toQueryParams(ds.params)
                    val items: List<HomeMediaItem> = runCatching {
                        when (ds.endpoint) {
                            "/api/shows"  -> apiClient.service.getShows(params).items.map { HomeMediaItem.ShowItem(it) }
                            "/api/movies" -> apiClient.service.getMovies(params).items.map { HomeMediaItem.MovieItem(it) }
                            else -> throw IllegalStateException("unrecognized shelf dataSource endpoint \"${ds.endpoint}\"")
                        }
                    }.getOrDefault(emptyList())
                    id to items
                }
            }.awaitAll()
        }
        rowItems = results.toMap()
    }

    companion object {
        fun factory(apiClient: ApiClient) = viewModelFactory {
            initializer { HomeViewModel(apiClient) }
        }
    }
}
