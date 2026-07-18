package com.pantheon.android.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.Episode
import com.pantheon.android.api.dto.MediaLanguages
import com.pantheon.android.api.dto.MovieDetail
import com.pantheon.android.api.dto.ResolvedPlayTarget
import com.pantheon.android.api.dto.ShowDetail
import com.pantheon.android.api.dto.TvZone
import kotlinx.coroutines.launch

data class SeasonGroup(val number: Int, val name: String, val episodes: List<Episode>)

// Kotlin counterpart of hades/src/tv/useZoneManifest.ts('detail') +
// components/media/useMediaDetail.ts. Only the 'season' episode grouping is
// implemented (sorted by season/episode number) — useMediaDetail.ts's
// 'aired' interleaving (specials positioned between numbered seasons by air
// date) is real complexity with no UI-affecting behavior difference beyond
// ordering; not built this pass, see episode_display_order note below.
class DetailViewModel(private val apiClient: ApiClient, private val contentType: String, private val id: String) : ViewModel() {

    var zones by mutableStateOf<List<TvZone>>(emptyList())
        private set
    fun hasZone(zoneId: String) = zones.any { it.id == zoneId }

    var loading by mutableStateOf(true)
        private set
    var show by mutableStateOf<ShowDetail?>(null)
        private set
    var movie by mutableStateOf<MovieDetail?>(null)
        private set
    var seasons by mutableStateOf<List<SeasonGroup>>(emptyList())
        private set
    var languages by mutableStateOf<MediaLanguages?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val title: String get() = show?.title ?: movie?.title ?: ""
    val overview: String get() = show?.overview ?: movie?.overview ?: ""
    val year: Int? get() = show?.year ?: movie?.year
    val genres: List<String> get() = (show?.genres ?: movie?.genres).orEmpty()
    val rating: Double? get() = show?.audienceRating ?: movie?.audienceRating
    val thumb: String? get() = show?.thumb ?: movie?.thumb
    val art: String? get() = show?.art ?: movie?.art

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            loading = true
            errorMessage = null
            try {
                zones = runCatching { apiClient.service.getTvManifest().detail.zones.sortedBy { it.order } }.getOrDefault(emptyList())
                if (contentType == "show") {
                    show = apiClient.service.getShow(id)
                    val episodes = runCatching { apiClient.service.getEpisodes(id) }.getOrDefault(emptyList())
                    val seasonNames = (show?.seasons.orEmpty()).associate { it.number to (it.name ?: "Season ${it.number}") }
                    seasons = episodes.groupBy { it.season }
                        .toSortedMap()
                        .map { (num, eps) -> SeasonGroup(num, seasonNames[num] ?: "Season $num", eps.sortedBy { it.episode }) }
                    languages = runCatching { apiClient.service.getShowLanguages(id) }.getOrNull()
                } else {
                    movie = apiClient.service.getMovie(id)
                    languages = runCatching { apiClient.service.getMovieLanguages(id) }.getOrNull()
                }
            } catch (e: Exception) {
                errorMessage = "Couldn't load: ${e.message ?: "unknown error"}"
            } finally {
                loading = false
            }
        }
    }

    // Movies play directly; shows resolve through the server-side
    // resolve-play-target branch (resume in-progress / next episode /
    // episode 1) — no client ever ports that algorithm, see
    // hades/src/player/resolvePlayTarget.ts's own comment.
    suspend fun resolvePlayTarget(): ResolvedPlayTarget? {
        if (contentType == "movie") return ResolvedPlayTarget(kind = "movie", id = id, positionMs = 0)
        return runCatching { apiClient.service.getResolvedPlayTarget(id) }.getOrNull()
    }

    companion object {
        fun factory(apiClient: ApiClient, contentType: String, id: String) = viewModelFactory {
            initializer { DetailViewModel(apiClient, contentType, id) }
        }
    }
}
