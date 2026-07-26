package com.pantheon.android.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.TvDataSource
import com.pantheon.android.api.dto.TvHeroDataSources
import com.pantheon.android.api.dto.TvHomeRow
import com.pantheon.android.api.dto.WatchProgress
import com.pantheon.android.api.dto.WatchTogetherSession
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
    // Every currently-open Watch Together session (any account, not just
    // this viewer's own) — see kairos's GET /api/watch-together/active and
    // hades' own WatchTogetherShelf on HomePage.tsx, the same discovery
    // surface this mirrors.
    var watchTogether by mutableStateOf<List<WatchTogetherSession>>(emptyList())
        private set

    // Optimistic local removal after this viewer closes/leaves one from the
    // shelf — mirrors HomePage.tsx's onCloseWatchTogether, avoiding a full
    // re-fetch just to drop one row.
    fun removeWatchTogether(sessionId: String) {
        watchTogether = watchTogether.filter { it.sessionId != sessionId }
    }

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

                // Every shelf row the manifest declares, not a fixed
                // client-side allowlist — a shelf Kairos adds/removes only
                // needs a DB row (see tv_shelf's v81 seed comment), no
                // client release. continue-watching is a "shelf"-typed row
                // too, but its data comes from a dedicated endpoint
                // (getWatchProgress), so it's fetched separately below.
                val cwRow = rows.find { it.id == "continue-watching" }
                continueWatching = if (cwRow != null) {
                    runCatching { apiClient.service.getWatchProgress() }.getOrDefault(emptyList())
                } else emptyList()

                watchTogether = runCatching { apiClient.service.getActiveWatchTogether() }.getOrDefault(emptyList())

                val shelfRows = rows.filter { it.type == "shelf" && it.id != "continue-watching" }
                fetchShelfRows(shelfRows)

                // The hero row declares its own two data sources rather than
                // reusing whatever recent-shows/recent-movies happen to be
                // configured with (see TvManifestService.cpp's heroRowJson
                // comment: hero's candidates are a fixed, art-filtered
                // shows+movies merge, not derived from another row).
                val heroSources = rows.find { it.type == "hero" }?.dataSources
                heroCandidates = if (heroSources != null) {
                    fetchHeroCandidates(heroSources)
                } else {
                    val shows = rowItems["recent-shows"].orEmpty()
                    val movies = rowItems["recent-movies"].orEmpty()
                    (shows + movies).filter { it.art != null }.ifEmpty { shows }
                }
                heroIndex = 0
            } catch (e: Exception) {
                errorMessage = "Couldn't load Home: ${e.message ?: "unknown error"}"
            } finally {
                loading = false
            }
        }
    }

    private suspend fun fetchShelfRows(rows: List<TvHomeRow>) {
        val results = coroutineScope {
            rows.map { row -> async { row.id to fetchDataSource(row.dataSource) } }.awaitAll()
        }
        rowItems = results.toMap()
    }

    private suspend fun fetchHeroCandidates(sources: TvHeroDataSources): List<HomeMediaItem> {
        val (shows, movies) = coroutineScope {
            val showsDeferred = async { fetchDataSource(sources.shows) }
            val moviesDeferred = async { fetchDataSource(sources.movies) }
            showsDeferred.await() to moviesDeferred.await()
        }
        return (shows + movies).filter { it.art != null }.ifEmpty { shows }
    }

    // The endpoint vocabulary a shelf/hero dataSource can point at — same
    // "which fields/endpoints exist is server-owned data" split every other
    // manifest zone follows. An endpoint this client doesn't recognize
    // degrades to an empty (rather than crashing) row, same as any other
    // manifest field a client doesn't yet understand.
    private suspend fun fetchDataSource(ds: TvDataSource?): List<HomeMediaItem> {
        if (ds?.endpoint == null) return emptyList()
        val params = toQueryParams(ds.params)
        return runCatching {
            when (ds.endpoint) {
                "/api/shows"  -> apiClient.service.getShows(params).items.map { HomeMediaItem.ShowItem(it) }
                "/api/movies" -> apiClient.service.getMovies(params).items.map { HomeMediaItem.MovieItem(it) }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        fun factory(apiClient: ApiClient) = viewModelFactory {
            initializer { HomeViewModel(apiClient) }
        }
    }
}
