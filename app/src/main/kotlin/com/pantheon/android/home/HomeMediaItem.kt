package com.pantheon.android.home

import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.Movie
import com.pantheon.android.api.dto.ShelfTile
import com.pantheon.android.api.dto.ShelfTileLatestEpisode
import com.pantheon.android.api.dto.Show

// Thin rendering wrapper around ShelfTile (GET /api/tv/shelf-items' tile
// shape — see KairosApi.getShelfItems). content_type is already an explicit
// "show"|"movie"|"episode" field on the wire now, so this no longer needs
// the old sealed-class ShowItem/MovieItem split (TvHome.tsx's isShow()
// equivalent) — one class handles all three, including episode tiles from a
// mixed shelf, which the old two-variant design had no room for at all.
data class HomeMediaItem(val tile: ShelfTile) {
    val id get() = tile.id
    val title get() = tile.title
    val year get() = tile.year
    val thumb get() = tile.thumb
    val art get() = tile.art
    val rating get() = tile.audienceRating
    val contentType get() = tile.contentType
    val latestEpisode: ShelfTileLatestEpisode? get() = tile.latestEpisode

    // Episode tiles only (a mixed shelf's per-episode items) — parent show
    // title + S/E code, displayed instead of title/year, same convention as
    // hades' mixedToShelfEntry.
    val showTitle get() = tile.showTitle
    val episodeCode: String?
        get() = if (tile.contentType == "episode")
            "S${tile.season.toString().padStart(2, '0')}E${tile.episode.toString().padStart(2, '0')}"
        else null
}

// Mirrors TvHome.tsx's thumbUrl()/artUrl(): `thumb`/`art` on the tile are
// presence flags (a Plex-relative-path-or-custom-URL string, truthy-
// checked), not the actual displayable URL — that's always the Kairos proxy
// endpoint, /api/{shows|movies|episodes}/:id/{thumb|art}, run through
// ApiClient's mediaUrl() for the server origin + auth token.
private fun HomeMediaItem.proxyUrl(apiClient: ApiClient, kind: String, present: String?): String? {
    if (present == null) return null
    return apiClient.mediaUrl("/api/${contentType}s/$id/$kind")
}

// Show/Movie -> HomeMediaItem — lets LibraryScreen.kt (both flavors) reuse
// the same MediaCard/ShelfZone components Home uses for its own shelves,
// same reasoning as hades' showToMixed/movieToMixed in HomePage.tsx (a
// uniform tile shape regardless of source, rather than one card variant per
// content type).
fun Show.toShelfTile() = HomeMediaItem(ShelfTile(
    contentType = "show", id = showId, title = title, thumb = thumb, art = art,
    year = year, audienceRating = audienceRating,
    latestEpisode = latestEpisode?.let {
        ShelfTileLatestEpisode(it.episodeId, it.season, it.episode, it.airDate ?: "")
    },
))

fun Movie.toShelfTile() = HomeMediaItem(ShelfTile(
    contentType = "movie", id = movieId, title = title, thumb = thumb, art = art,
    year = year, audienceRating = audienceRating,
))

fun HomeMediaItem.thumbUrl(apiClient: ApiClient): String? = proxyUrl(apiClient, "thumb", thumb)

// Hero-only (show/movie) — there's no /api/episodes/:id/art route (episode
// tiles never carry their own art, only a parent-show-derived one), and
// episode tiles never become hero candidates in the first place (see
// HomeViewModel's fetchShelfItems), so this never actually gets called with one.
fun HomeMediaItem.artUrl(apiClient: ApiClient): String? = proxyUrl(apiClient, "art", art)