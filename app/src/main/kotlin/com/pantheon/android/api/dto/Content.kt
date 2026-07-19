package com.pantheon.android.api.dto

import com.google.gson.annotations.SerializedName

// Mirrors hades/src/api/types.ts's Show/Movie shapes — only the fields Home
// actually renders, not every field ContentService.cpp's /api/shows and
// /api/movies can return (match_status, source_base_url, etc. are
// admin-tooling concerns /tv itself doesn't surface either).

data class Show(
    @SerializedName("show_id") val showId: String,
    val title: String,
    val year: Int? = null,
    val thumb: String? = null,
    val art: String? = null,
    @SerializedName("audience_rating") val audienceRating: Double? = null,
    @SerializedName("latest_episode") val latestEpisode: LatestEpisode? = null,
)

data class Movie(
    @SerializedName("movie_id") val movieId: String,
    val title: String,
    val year: Int? = null,
    val thumb: String? = null,
    val art: String? = null,
    @SerializedName("audience_rating") val audienceRating: Double? = null,
)

// Only populated when a /api/shows request sorts by recently_aired — see
// ContentService.cpp's N+1 lookup and hades' Show type's same field.
data class LatestEpisode(
    @SerializedName("episode_id") val episodeId: String,
    val season: Int,
    val episode: Int,
    @SerializedName("air_date") val airDate: String? = null,
)

data class WatchProgress(
    @SerializedName("content_type") val contentType: String, // "movie" | "episode"
    @SerializedName("content_id") val contentId: String,
    @SerializedName("show_id") val showId: String? = null,
    val title: String,
    @SerializedName("show_title") val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerializedName("position_ms") val positionMs: Long,
    @SerializedName("duration_ms") val durationMs: Long,
    @SerializedName("up_next") val upNext: Boolean = false,
)

data class PagedResult<T>(
    val items: List<T>,
    val total: Int,
)

// ── Detail (GET /api/shows/:id, /api/movies/:id) ────────────────────────────
// Mirrors hades/src/api/types.ts's ShowDetail/MovieDetail — only the fields
// Detail actually renders, same trimming rationale as Show/Movie above.
// List fields stay nullable with a null default rather than a non-null
// default (e.g. emptyList()) — Gson deserializes Kotlin data classes via
// unsafe allocation, bypassing the constructor, so a field absent from the
// JSON response is left null at runtime regardless of its declared Kotlin
// default; TvManifest.kt's filterFields/actions already establish this same
// nullable-list convention for the same reason.

data class ShowDetail(
    @SerializedName("show_id") val showId: String,
    val title: String,
    val overview: String? = null,
    val year: Int? = null,
    val genres: List<String>? = null,
    val thumb: String? = null,
    val art: String? = null,
    @SerializedName("audience_rating") val audienceRating: Double? = null,
    val seasons: List<SeasonRef>? = null,
)

data class SeasonRef(val number: Int, val name: String? = null)

data class MovieDetail(
    @SerializedName("movie_id") val movieId: String,
    val title: String,
    val overview: String? = null,
    val year: Int? = null,
    val genres: List<String>? = null,
    val thumb: String? = null,
    val art: String? = null,
    @SerializedName("audience_rating") val audienceRating: Double? = null,
)

data class Episode(
    @SerializedName("episode_id") val episodeId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String? = null,
    @SerializedName("air_date") val airDate: String? = null,
    val thumb: String? = null,
)

// GET /api/episodes/:id/next-episode -- null if this is the last episode
// (ContentRepository::getNextEpisode). Mirrors hades/src/api/types.ts's
// NextEpisode; drives the Player's Up Next overlay (see PlayerScreen.kt).
data class NextEpisode(
    @SerializedName("episode_id") val episodeId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    @SerializedName("duration_ms") val durationMs: Long,
    val overview: String? = null,
    @SerializedName("air_date") val airDate: String? = null,
    val thumb: String? = null,
)

// GET /api/shows/:id/languages, /api/movies/:id/languages — ISO 639-2 codes,
// mirrors hades/src/api/types.ts's MediaLanguages. Can legitimately be long
// (a well-tagged anime rip may embed 8-10 dub/subtitle languages) — see
// DetailViewModel's own comment on why this needs to be a distinct field
// from genres, which never gets long enough to need clamping.
data class MediaLanguages(val audio: List<String>? = null, val subtitle: List<String>? = null)

// ── Library screen ───────────────────────────────────────────────────────────

data class LibraryWithSource(
    @SerializedName("library_id") val libraryId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("library_type") val libraryType: String,
)

// GET /api/metadata/values?field=X — { values: string[] }, see
// hades/src/api/client.ts's getFilterValues.
data class FilterValuesResponse(val values: List<String>? = null)
