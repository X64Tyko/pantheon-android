package com.pantheon.android.player

import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.KairosApi
import com.pantheon.android.api.dto.VodStartRequest
import com.pantheon.android.api.dto.VodStartResponse
import com.pantheon.android.api.dto.WatchProgressBody
import com.pantheon.android.api.dto.WatchTogetherHeartbeatBody
import com.pantheon.android.api.dto.WatchTogetherSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

// Regression coverage for the exact bug CHANGELOG.md's Unreleased section
// documents: PlayerScreen.kt used to hardcode ExoPlayer's MediaItem start
// position to 0L for every VOD session instead of feeding it
// PlayerViewModel.basePositionMs (which was already tracked correctly, just
// never read by the player). This suite pins basePositionMs itself — the
// exact value PlayerScreen now passes to exoPlayer.setMediaItem(item,
// basePositionMs) — plus reportProgress(), the other place a wrong
// basePositionMs would silently corrupt watch-progress pings.
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private lateinit var apiClient: ApiClient
    private lateinit var service: KairosApi

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        service = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        every { apiClient.service } returns service
        coEvery { service.startVodPlayback(any()) } returns
            VodStartResponse(sessionId = "sess-1", manifestUrl = "/stream/hls/sess-1/master.m3u8", durationMs = 3_600_000L, title = "A Movie")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(initialPositionMs: Long, kind: String = "movie") =
        PlayerViewModel(apiClient, kind, "content-1", initialPositionMs, wtSessionId = null)

    @Test
    fun `non-zero initial position becomes the seek target basePositionMs`() {
        val viewModel = newViewModel(initialPositionMs = 754_000L)
        assertEquals(754_000L, viewModel.basePositionMs)
    }

    @Test
    fun `zero initial position stays zero (fresh playback isn't force-resumed)`() {
        val viewModel = newViewModel(initialPositionMs = 0L)
        assertEquals(0L, viewModel.basePositionMs)
    }

    @Test
    fun `startVodPlayback is called with the resume position, not a hardcoded 0`() = runTest {
        newViewModel(initialPositionMs = 754_000L)
        coVerify { service.startVodPlayback(VodStartRequest(contentType = "movie", contentId = "content-1", positionMs = 754_000L)) }
    }

    @Test
    fun `reportProgress adds the player-relative position onto basePositionMs`() = runTest {
        val viewModel = newViewModel(initialPositionMs = 754_000L)
        val body = slot<WatchProgressBody>()
        coEvery { service.putWatchProgress("movie", "content-1", capture(body)) } returns mockk(relaxed = true)
        // durationMs comes back from the stubbed startVodPlayback response above.
        viewModel.reportProgress(playerPositionMs = 10_000L)
        // deviceType is derived from BuildConfig.FLAVOR (android-mobile/android-tv
        // depending on which variant's unit-test task runs this), so it's
        // deliberately not asserted here — only the resume-position math is.
        assertEquals(764_000L, body.captured.positionMs)
        assertEquals(3_600_000L, body.captured.durationMs)
    }

    @Test
    fun `reportProgress with a fresh (0-based) session still reports the raw player position`() = runTest {
        val viewModel = newViewModel(initialPositionMs = 0L)
        val body = slot<WatchProgressBody>()
        coEvery { service.putWatchProgress("movie", "content-1", capture(body)) } returns mockk(relaxed = true)
        viewModel.reportProgress(playerPositionMs = 5_000L)
        assertEquals(5_000L, body.captured.positionMs)
    }

    // ── Watch Together heartbeat: host-only dispatch ────────────────────────

    private fun newHostViewModel(): PlayerViewModel {
        every { apiClient.currentUserId } returns "user-host"
        coEvery { service.getWatchTogether("wt-1") } returns
            WatchTogetherSession(
                sessionId = "wt-1", hostUserId = "user-host", hostUsername = "host",
                contentType = "movie", contentId = "content-1", title = "A Movie", createdAt = 0L,
            )
        return PlayerViewModel(apiClient, "movie", "content-1", 0L, wtSessionId = "wt-1")
    }

    private fun newFollowerViewModel(): PlayerViewModel {
        every { apiClient.currentUserId } returns "user-follower"
        coEvery { service.getWatchTogether("wt-1") } returns
            WatchTogetherSession(
                sessionId = "wt-1", hostUserId = "user-host", hostUsername = "host",
                contentType = "movie", contentId = "content-1", title = "A Movie", createdAt = 0L,
            )
        return PlayerViewModel(apiClient, "movie", "content-1", 0L, wtSessionId = "wt-1")
    }

    @Test
    fun `host heartbeat actually posts to Hermes`() = runTest {
        val viewModel = newHostViewModel()
        viewModel.sendWtHeartbeat(positionMs = 12_000L, paused = false)
        coVerify { service.postWatchTogetherHeartbeat("wt-1", WatchTogetherHeartbeatBody(12_000L, false)) }
    }

    @Test
    fun `follower heartbeat is a no-op — only the host drives Hermes' baseline`() = runTest {
        val viewModel = newFollowerViewModel()
        viewModel.sendWtHeartbeat(positionMs = 12_000L, paused = false)
        coVerify(exactly = 0) { service.postWatchTogetherHeartbeat(any(), any()) }
    }

    @Test
    fun `follower command dispatch is also a no-op`() = runTest {
        val viewModel = newFollowerViewModel()
        viewModel.sendWtCommand("seek", 5_000L)
        coVerify(exactly = 0) { service.postWatchTogetherCommand(any(), any()) }
    }
}
