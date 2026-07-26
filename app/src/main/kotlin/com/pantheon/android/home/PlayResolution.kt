package com.pantheon.android.home

import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.ResolvedPlayTarget
import com.pantheon.android.api.dto.ShowWatchState

// "Play" from any shelf resumes real progress, not just the dedicated
// Continue Watching row — every content_type has a server-side
// resolve-play-target lookup (movies included, see kairos's
// PlaybackService.cpp) so no call site needs to special-case position 0.
// Pulled out of both flavors' HomeScreen.kt (which used to duplicate this
// exact branch) so it's unit-testable without Compose — mirrors
// DetailViewModel.resolvePlayTarget()'s own movie/show split.
suspend fun resolveShelfPlayTarget(apiClient: ApiClient, contentType: String, id: String): ResolvedPlayTarget? {
    return if (contentType == "movie") {
        runCatching { apiClient.service.getResolvedMoviePlayTarget(id) }.getOrNull()
    } else {
        runCatching { apiClient.service.getResolvedPlayTarget(id) }.getOrNull()
    }
}

// PLAY_LATEST_EPISODE shelf action (e.g. a "New Episodes" row): the show's
// resume target might not be this specific latest episode (viewer could be
// behind on an earlier one) — only resume if the show's own watch-state is
// actually sitting on this episode already; otherwise it's unwatched, start
// at 0. Pure — no IO — so it's directly testable, unlike the suspend fetch
// that produces its `state` input.
fun resolveLatestEpisodePosition(state: ShowWatchState?, latestEpisodeId: String): Long {
    return if (state?.contentId == latestEpisodeId && !state.completed) state.positionMs else 0L
}
