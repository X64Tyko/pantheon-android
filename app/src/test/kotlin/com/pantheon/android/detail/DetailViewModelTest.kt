package com.pantheon.android.detail

import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.KairosApi
import com.pantheon.android.api.dto.Episode
import com.pantheon.android.api.dto.MediaLanguages
import com.pantheon.android.api.dto.MovieDetail
import com.pantheon.android.api.dto.ResolvedPlayTarget
import com.pantheon.android.api.dto.SeasonRef
import com.pantheon.android.api.dto.ShowDetail
import com.pantheon.android.api.dto.TvHomeSection
import com.pantheon.android.api.dto.TvManifest
import com.pantheon.android.api.dto.TvZoneSection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// Regression coverage for CHANGELOG.md's Unreleased "Play" resolve-play-target
// fixes: a movie's "Play" action used to hardcode position_ms=0 instead of
// calling GET /api/movies/:id/resolve-play-target, and the new "Play from
// Beginning" action must bypass that lookup entirely (unlike regular Play).
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private lateinit var apiClient: ApiClient
    private lateinit var service: KairosApi
    private val emptyManifest = TvManifest(
        version = 1,
        home = TvHomeSection(emptyList()),
        library = TvZoneSection(emptyList()),
        detail = TvZoneSection(emptyList()),
        guide = TvZoneSection(emptyList()),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        service = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        io.mockk.every { apiClient.service } returns service
        coEvery { service.getTvManifest() } returns emptyManifest
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newMovieViewModel(movieId: String = "movie-1"): DetailViewModel {
        coEvery { service.getMovie(movieId) } returns
            MovieDetail(movieId = movieId, title = "A Movie")
        coEvery { service.getMovieLanguages(movieId) } returns MediaLanguages()
        return DetailViewModel(apiClient, "movie", movieId)
    }

    // ── resolvePlayTarget() — regular "Play" ────────────────────────────────

    @Test
    fun `movie with real watch progress resumes at the resolved position`() = runTest {
        val viewModel = newMovieViewModel()
        coEvery { service.getResolvedMoviePlayTarget("movie-1") } returns
            ResolvedPlayTarget(kind = "movie", id = "movie-1", positionMs = 812_000L)

        val target = viewModel.resolvePlayTarget()

        assertEquals(812_000L, target?.positionMs)
        assertEquals("movie", target?.kind)
        assertEquals("movie-1", target?.id)
    }

    @Test
    fun `movie with no progress (server returns null) starts at 0`() = runTest {
        val viewModel = newMovieViewModel()
        coEvery { service.getResolvedMoviePlayTarget("movie-1") } returns null

        val target = viewModel.resolvePlayTarget()

        assertEquals(0L, target?.positionMs)
        assertEquals("movie", target?.kind)
    }

    @Test
    fun `movie resolve-play-target lookup failing still falls back to position 0 instead of failing Play`() = runTest {
        val viewModel = newMovieViewModel()
        coEvery { service.getResolvedMoviePlayTarget("movie-1") } throws RuntimeException("network error")

        val target = viewModel.resolvePlayTarget()

        assertEquals(0L, target?.positionMs)
    }

    @Test
    fun `show Play resolves through the show's own resolve-play-target endpoint`() = runTest {
        coEvery { service.getShow("show-1") } returns ShowDetail(showId = "show-1", title = "A Show")
        coEvery { service.getEpisodes("show-1") } returns emptyList()
        coEvery { service.getShowLanguages("show-1") } returns MediaLanguages()
        val viewModel = DetailViewModel(apiClient, "show", "show-1")
        coEvery { service.getResolvedPlayTarget("show-1") } returns
            ResolvedPlayTarget(kind = "episode", id = "ep-5", positionMs = 300_000L)

        val target = viewModel.resolvePlayTarget()

        assertEquals("episode", target?.kind)
        assertEquals("ep-5", target?.id)
        assertEquals(300_000L, target?.positionMs)
    }

    // ── playFromBeginningTarget() — deliberately bypasses resolve-play-target ──

    @Test
    fun `playFromBeginningTarget for a movie is always position 0 and never calls resolve-play-target`() = runTest {
        val viewModel = newMovieViewModel()

        val target = viewModel.playFromBeginningTarget()

        assertEquals("movie", target?.kind)
        assertEquals("movie-1", target?.id)
        assertEquals(0L, target?.positionMs)
        coVerify(exactly = 0) { service.getResolvedMoviePlayTarget(any()) }
    }

    @Test
    fun `playFromBeginningTarget for a show resolves episode 1 regardless of progress, without calling resolve-play-target`() = runTest {
        val episodes = listOf(
            Episode(episodeId = "ep-1", season = 1, episode = 1, title = "Pilot"),
            Episode(episodeId = "ep-2", season = 1, episode = 2, title = "Second"),
        )
        coEvery { service.getShow("show-1") } returns
            ShowDetail(showId = "show-1", title = "A Show", seasons = listOf(SeasonRef(1)))
        coEvery { service.getEpisodes("show-1") } returns episodes
        coEvery { service.getShowLanguages("show-1") } returns MediaLanguages()
        val viewModel = DetailViewModel(apiClient, "show", "show-1")

        // Viewer is mid-way through episode 2 — regular Play would resume
        // there, but "Play from Beginning" must ignore that entirely.
        val target = viewModel.playFromBeginningTarget()

        assertEquals("episode", target?.kind)
        assertEquals("ep-1", target?.id)
        assertEquals(0L, target?.positionMs)
        coVerify(exactly = 0) { service.getResolvedPlayTarget(any()) }
    }

    @Test
    fun `playFromBeginningTarget for a show with no episodes yet loaded returns null`() = runTest {
        coEvery { service.getShow("show-1") } returns ShowDetail(showId = "show-1", title = "A Show")
        coEvery { service.getEpisodes("show-1") } returns emptyList()
        coEvery { service.getShowLanguages("show-1") } returns MediaLanguages()
        val viewModel = DetailViewModel(apiClient, "show", "show-1")

        assertNull(viewModel.playFromBeginningTarget())
    }
}
