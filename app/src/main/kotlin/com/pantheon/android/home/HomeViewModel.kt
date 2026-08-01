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
import com.pantheon.android.api.dto.WatchTogetherSession
import com.pantheon.android.util.toQueryParams
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Kotlin counterpart of hades/src/tv/useHomeManifest.ts + the manifest-driven
// half of TvHome.tsx's own state. Fetches GET /api/tv/manifest, then resolves
// each shelf/hero row's opaque `filter` into real tiles via the one generic
// GET /api/tv/shelf-items call every manifest consumer makes — nothing here
// is Android-specific business logic, just the same contract
// hades/src/tv/TvHome.tsx implements in TypeScript.
//
// Unlike the old design (a dataSource.endpoint string this client had to
// recognize, switching between /api/shows and /api/movies itself), this
// never branches on what a shelf "is" — the server decides how to resolve
// filter into tiles, so a new shelf shape (e.g. a "mixed" shelf, which the
// old dataSource.endpoint design had no way to express at all — it would
// silently fall back to an empty/wrong row) never needs a client change.
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
    // surface this mirrors. Only fetched when a 'watch-together' row is
    // actually present in the manifest (kairos v105) — same "presence in
    // rows gates the fetch" treatment continue-watching/hero already get,
    // rather than the unconditional fetch this used to be.
    var watchTogether by mutableStateOf<List<WatchTogetherSession>>(emptyList())
        private set

    // Gates the Home screen's "Library" quick-action the same way rows.any
    // { type == "guide" } already gates "Guide" — an instance with zero
    // library zones configured (kairos tv_zone) has nothing for that button
    // to lead to. Library is a whole separate manifest section (not a home
    // row), so this needs its own flag rather than piggybacking on `rows`.
    var hasLibraryZones by mutableStateOf(false)
        private set

    // Optimistic local removal after this viewer closes/leaves one from the
    // shelf — mirrors HomePage.tsx's onCloseWatchTogether, avoiding a full
    // re-fetch just to drop one row.
    fun removeWatchTogether(sessionId: String) {
        watchTogether = watchTogether.filter { it.sessionId != sessionId }
    }

    // Server-resolved (art-preferred shows+movies) — the hero row's own
    // "hero" content_type filter, see fetchShelfItems below.
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
                hasLibraryZones = manifest.library.zones.isNotEmpty()

                // Every shelf row the manifest declares, not a fixed
                // client-side allowlist — a shelf Kairos adds/removes only
                // needs a DB row (see tv_shelf's v81 seed comment), no
                // client release. continue-watching is a "shelf"-typed row
                // too, but its data comes from a dedicated endpoint
                // (getWatchProgress), so it's fetched separately below —
                // it never goes through the generic filter mechanism.
                val cwRow = rows.find { it.id == "continue-watching" }
                continueWatching = if (cwRow != null) {
                    runCatching { apiClient.service.getWatchProgress() }.getOrDefault(emptyList())
                } else emptyList()

                watchTogether = if (rows.any { it.type == "watch-together" }) {
                    runCatching { apiClient.service.getActiveWatchTogether() }.getOrDefault(emptyList())
                } else emptyList()

                val shelfRows = rows.filter { it.type == "shelf" && it.id != "continue-watching" }
                fetchShelfRows(shelfRows)

                // Hero's art-filtered shows+movies merge is resolved
                // server-side (GET /api/tv/shelf-items' "hero" content_type
                // branch — see TvManifestService.cpp) instead of this client
                // fetching two sources and merging them itself.
                val heroRow = rows.find { it.type == "hero" }
                heroCandidates = if (heroRow != null) fetchShelfItems(heroRow.filter) else emptyList()
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
            rows.map { row -> async { row.id to fetchShelfItems(row.filter) } }.awaitAll()
        }
        rowItems = results.toMap()
    }

    // The one generic fetch every shelf/hero row goes through, regardless of
    // what its filter's content_type says. `filter` is forwarded verbatim as
    // query params (via toQueryParams) — never inspected here. Any failure
    // (network, an unrecognized/empty filter) degrades to an empty list
    // rather than crashing the whole screen, same posture the old
    // per-endpoint fetch had.
    private suspend fun fetchShelfItems(filter: Map<String, Any>?): List<HomeMediaItem> {
        val params = toQueryParams(filter)
        return runCatching {
            apiClient.service.getShelfItems(params).items.map { HomeMediaItem(it) }
        }.getOrDefault(emptyList())
    }

    companion object {
        fun factory(apiClient: ApiClient) = viewModelFactory {
            initializer { HomeViewModel(apiClient) }
        }
    }
}