package com.pantheon.android.home

import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.Movie
import com.pantheon.android.api.dto.Show

// Kotlin equivalent of TvHome.tsx's `Show | Movie` structural union + its
// isShow() type guard — a small sealed wrapper instead, since Kotlin doesn't
// have TypeScript's structural typing. contentType/contentId match the
// wire values PlayerPage/watch-progress endpoints expect ("show"/"movie").
sealed interface HomeMediaItem {
    val id: String
    val title: String
    val year: Int?
    val thumb: String?
    val art: String?
    val rating: Double?
    val contentType: String

    data class ShowItem(val show: Show) : HomeMediaItem {
        override val id get() = show.showId
        override val title get() = show.title
        override val year get() = show.year
        override val thumb get() = show.thumb
        override val art get() = show.art
        override val rating get() = show.audienceRating
        override val contentType get() = "show"
    }

    data class MovieItem(val movie: Movie) : HomeMediaItem {
        override val id get() = movie.movieId
        override val title get() = movie.title
        override val year get() = movie.year
        override val thumb get() = movie.thumb
        override val art get() = movie.art
        override val rating get() = movie.audienceRating
        override val contentType get() = "movie"
    }
}

// Mirrors TvHome.tsx's thumbUrl()/artUrl() exactly: `thumb`/`art` on the DTO
// are presence flags (a Plex-relative-path-or-custom-URL string, truthy-
// checked), not the actual displayable URL — that's always the Kairos proxy
// endpoint, /api/{shows|movies}/:id/{thumb|art}, run through ApiClient's
// mediaUrl() for the server origin + auth token.
private fun HomeMediaItem.proxyUrl(apiClient: ApiClient, kind: String, present: String?): String? {
    if (present == null) return null
    val segment = if (this is HomeMediaItem.ShowItem) "shows" else "movies"
    return apiClient.mediaUrl("/api/$segment/$id/$kind")
}

fun HomeMediaItem.thumbUrl(apiClient: ApiClient): String? = proxyUrl(apiClient, "thumb", thumb)
fun HomeMediaItem.artUrl(apiClient: ApiClient): String? = proxyUrl(apiClient, "art", art)
