package com.pantheon.android.home

import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.KairosApi
import com.pantheon.android.api.dto.ResolvedPlayTarget
import com.pantheon.android.api.dto.ShowWatchState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Regression coverage for CHANGELOG.md's Unreleased "Play from a shelf ...
// ignored watch progress for movies" fix. This logic used to be duplicated
// inline in both flavors' HomeScreen.kt Composables (resolveAndPlay /
// shelfItemPlay / onShelfItemClick) — pulled into PlayResolution.kt so it's
// testable without Compose; both HomeScreen.kt files now call these
// functions directly.
class PlayResolutionTest {

    private val service: KairosApi = mockk(relaxed = true)
    private val apiClient: ApiClient = mockk(relaxed = true)

    init {
        every { apiClient.service } returns service
    }

    // ── resolveShelfPlayTarget ───────────────────────────────────────────────

    @Test
    fun `movie shelf Play resolves through the movie resolve-play-target endpoint`() = runTest {
        coEvery { service.getResolvedMoviePlayTarget("movie-1") } returns
            ResolvedPlayTarget(kind = "movie", id = "movie-1", positionMs = 640_000L)

        val target = resolveShelfPlayTarget(apiClient, "movie", "movie-1")

        assertEquals(640_000L, target?.positionMs)
    }

    @Test
    fun `show shelf Play resolves through the show resolve-play-target endpoint, not the movie one`() = runTest {
        coEvery { service.getResolvedPlayTarget("show-1") } returns
            ResolvedPlayTarget(kind = "episode", id = "ep-9", positionMs = 42_000L)

        val target = resolveShelfPlayTarget(apiClient, "show", "show-1")

        assertEquals("ep-9", target?.id)
        assertEquals(42_000L, target?.positionMs)
    }

    @Test
    fun `a failed lookup returns null rather than throwing`() = runTest {
        coEvery { service.getResolvedMoviePlayTarget("movie-1") } throws RuntimeException("boom")

        assertNull(resolveShelfPlayTarget(apiClient, "movie", "movie-1"))
    }

    // ── resolveLatestEpisodePosition (PLAY_LATEST_EPISODE shelf action) ─────

    @Test
    fun `viewer mid-way through the latest episode resumes at that position`() {
        val state = ShowWatchState(contentId = "ep-latest", positionMs = 555_000L, durationMs = 2_000_000L, completed = false, updatedAt = 0L)

        assertEquals(555_000L, resolveLatestEpisodePosition(state, "ep-latest"))
    }

    @Test
    fun `watch-state pointing at a different (earlier) episode starts the latest one at 0`() {
        // Viewer is behind — still on an earlier episode, not the shelf's "latest" one.
        val state = ShowWatchState(contentId = "ep-earlier", positionMs = 100_000L, durationMs = 2_000_000L, completed = false, updatedAt = 0L)

        assertEquals(0L, resolveLatestEpisodePosition(state, "ep-latest"))
    }

    @Test
    fun `a completed latest episode starts over at 0, not mid-credits`() {
        val state = ShowWatchState(contentId = "ep-latest", positionMs = 1_990_000L, durationMs = 2_000_000L, completed = true, updatedAt = 0L)

        assertEquals(0L, resolveLatestEpisodePosition(state, "ep-latest"))
    }

    @Test
    fun `no watch-state at all (never watched) starts at 0`() {
        assertEquals(0L, resolveLatestEpisodePosition(null, "ep-latest"))
    }
}
