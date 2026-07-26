package com.pantheon.android.guide

import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.KairosApi
import com.pantheon.android.api.dto.Channel
import com.pantheon.android.api.dto.EpgProgram
import com.pantheon.android.api.dto.PreviewStartResponse
import com.pantheon.android.api.dto.TvHomeSection
import com.pantheon.android.api.dto.TvManifest
import com.pantheon.android.api.dto.TvZone
import com.pantheon.android.api.dto.TvZoneSection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// GuideViewModel is documented as "the Kotlin counterpart of
// hades/src/guide/useGuideSession.ts" — this suite checks it actually
// behaves that way where it claims to (channel/EPG loading, focus state,
// findLiveProgram's live-boundary math, and beginPreview()'s mid-cold-start
// queuing, which now mirrors hades/src/guide/previewSessionController.ts's
// begin() — a channel focus that lands while a startPreview() is still in
// flight is queued via pendingChannelId and applied with switchPreview()
// once that start resolves, rather than racing a second startPreview() (which
// would orphan one of the two resulting ffmpeg processes) or silently
// dropping the focus (the bug this replaced — see git history on
// beginPreview() for the pre-fix behavior this test used to pin down).
//
// SELECT_DEBOUNCE_MS (300L below) is GuideViewModel.kt's own private
// constant of the same value, duplicated here since a file-private const
// isn't visible outside its declaring file even within the same package.
@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var apiClient: ApiClient
    private lateinit var service: KairosApi

    private val emptyManifest = TvManifest(
        version = 1,
        home = TvHomeSection(emptyList()),
        library = TvZoneSection(emptyList()),
        detail = TvZoneSection(emptyList()),
        guide = TvZoneSection(listOf(TvZone(id = "preview-panel", order = 1), TvZone(id = "time-grid", order = 2))),
    )

    private val ch1 = Channel(channelId = "c1", name = "CNN", number = 1)
    private val ch2 = Channel(channelId = "c2", name = "ESPN", number = 2)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        service = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        every { apiClient.service } returns service
        every { apiClient.streamUrl(any()) } answers { "http://test.local" + firstArg<String>() }
        coEvery { service.getTvManifest() } returns emptyManifest
        coEvery { service.getChannels() } returns listOf(ch2, ch1) // deliberately out of number order
        coEvery { service.getChannelEpg(any(), any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = GuideViewModel(apiClient)

    private fun program(
        id: String, startMs: Long, endMs: Long,
        itemType: String = "movie", title: String = "Program $id",
    ) = EpgProgram(itemId = id, itemType = itemType, title = title, wallClockStartMs = startMs, wallClockEndMs = endMs)

    private fun advancePastDebounce() {
        testDispatcher.scheduler.advanceTimeBy(301L)
        testDispatcher.scheduler.runCurrent()
    }

    // ── load() ───────────────────────────────────────────────────────────────

    @Test
    fun `channels load sorted by number regardless of API order`() {
        val vm = newViewModel()
        assertEquals(listOf("c1", "c2"), vm.channels.map { it.channelId })
    }

    @Test
    fun `loading is false once the initial load completes`() {
        val vm = newViewModel()
        assertFalse(vm.loading)
    }

    @Test
    fun `errorMessage is null on a successful load`() {
        val vm = newViewModel()
        assertNull(vm.errorMessage)
    }

    @Test
    fun `epgByChannel is populated per channel`() {
        coEvery { service.getChannelEpg("c1", any(), any()) } returns listOf(program("p1", 0, 1000))
        coEvery { service.getChannelEpg("c2", any(), any()) } returns listOf(program("p2", 0, 1000))
        val vm = newViewModel()
        assertEquals(1, vm.epgByChannel["c1"]?.size)
        assertEquals("p1", vm.epgByChannel["c1"]?.first()?.itemId)
        assertEquals("p2", vm.epgByChannel["c2"]?.first()?.itemId)
    }

    @Test
    fun `a single channel's EPG failure does not blank out the others`() {
        coEvery { service.getChannelEpg("c1", any(), any()) } throws RuntimeException("kairos down for c1")
        coEvery { service.getChannelEpg("c2", any(), any()) } returns listOf(program("p2", 0, 1000))
        val vm = newViewModel()
        assertEquals(emptyList<EpgProgram>(), vm.epgByChannel["c1"])
        assertEquals(1, vm.epgByChannel["c2"]?.size)
        // A partial per-channel failure is swallowed (runCatching), not
        // surfaced as a whole-screen error.
        assertNull(vm.errorMessage)
    }

    @Test
    fun `getChannels failure sets errorMessage and clears loading`() {
        coEvery { service.getChannels() } throws RuntimeException("network error")
        val vm = newViewModel()
        assertFalse(vm.loading)
        assertTrue(vm.errorMessage?.contains("network error") == true)
    }

    @Test
    fun `a manifest fetch failure does not fail the whole load — zones just stay empty`() {
        coEvery { service.getTvManifest() } throws RuntimeException("manifest unavailable")
        val vm = newViewModel()
        assertEquals(emptyList<TvZone>(), vm.zones)
        assertFalse(vm.loading)
        assertNull(vm.errorMessage)
        // Channels/EPG still load fine — manifest is only zone-gating info.
        assertEquals(2, vm.channels.size)
    }

    @Test
    fun `zones load sorted by order`() {
        val vm = newViewModel()
        assertEquals(listOf("preview-panel", "time-grid"), vm.zones.map { it.id })
    }

    @Test
    fun `hasZone reflects the loaded manifest zones`() {
        val vm = newViewModel()
        assertTrue(vm.hasZone("preview-panel"))
        assertFalse(vm.hasZone("channel-header"))
    }

    // ── focus / nowProgram ──────────────────────────────────────────────────

    @Test
    fun `focusedChannel resolves the full Channel from focusedChannelId`() {
        val vm = newViewModel()
        vm.selectChannel("c2")
        assertEquals("ESPN", vm.focusedChannel?.name)
    }

    @Test
    fun `selectProgram pins focusedProgram and selectChannel on a new channel clears it back to live`() {
        val vm = newViewModel()
        val p = program("p1", 0, 1000)
        vm.selectProgram("c1", p)
        assertEquals(p, vm.focusedProgram)
        assertEquals("c1", vm.focusedChannelId)

        vm.selectChannel("c2")
        assertNull(vm.focusedProgram)
        assertEquals("c2", vm.focusedChannelId)
    }

    @Test
    fun `selecting the already-focused channel again does not restart the preview session`() {
        coEvery { service.startPreview(any()) } returns PreviewStartResponse(sessionId = "s1", manifestUrl = "/m/s1.m3u8")
        val vm = newViewModel()
        vm.selectChannel("c1")
        advancePastDebounce()
        coVerify(exactly = 1) { service.startPreview(any()) }

        vm.selectChannel("c1") // same id again — selectChannel's own equality check short-circuits
        advancePastDebounce()
        coVerify(exactly = 1) { service.startPreview(any()) } // still just once
    }

    // ── live-boundary math (findLiveProgram — extracted for exactly this reason) ─

    @Test
    fun `findLiveProgram is live at the inclusive start boundary`() {
        val p = program("p1", 1000, 2000)
        assertEquals(p, findLiveProgram(listOf(p), 1000))
    }

    @Test
    fun `findLiveProgram is NOT live at the exclusive end boundary`() {
        val p = program("p1", 1000, 2000)
        assertNull(findLiveProgram(listOf(p), 2000))
    }

    @Test
    fun `findLiveProgram is live one ms before the end boundary`() {
        val p = program("p1", 1000, 2000)
        assertEquals(p, findLiveProgram(listOf(p), 1999))
    }

    @Test
    fun `findLiveProgram returns null for a gap between programs`() {
        val a = program("a", 0, 1000)
        val b = program("b", 2000, 3000)
        assertNull(findLiveProgram(listOf(a, b), 1500))
    }

    @Test
    fun `findLiveProgram returns null for an empty schedule`() {
        assertNull(findLiveProgram(emptyList(), 500))
    }

    // ── beginPreview / session lifecycle ────────────────────────────────────

    @Test
    fun `focusing a channel starts a preview session after the debounce, not immediately`() {
        coEvery { service.startPreview(any()) } returns PreviewStartResponse(sessionId = "s1", manifestUrl = "/m/s1.m3u8")
        val vm = newViewModel()
        vm.selectChannel("c1")
        coVerify(exactly = 0) { service.startPreview(any()) }
        advancePastDebounce()
        coVerify(exactly = 1) { service.startPreview(match { it.channelId == "c1" }) }
        assertEquals("http://test.local/m/s1.m3u8", vm.previewManifestUrl)
    }

    @Test
    fun `switching to a second channel on an already-live session reuses it via switchPreview`() {
        coEvery { service.startPreview(any()) } returns PreviewStartResponse(sessionId = "s1", manifestUrl = "/m/s1.m3u8")
        val vm = newViewModel()
        vm.selectChannel("c1")
        advancePastDebounce()

        vm.selectChannel("c2")
        advancePastDebounce()

        coVerify(exactly = 1) { service.startPreview(any()) } // never a second cold start
        coVerify { service.switchPreview("s1", match { it.channelId == "c2" }) }
        // Manifest URL is untouched by a plain switch — same server-side
        // stability guarantee PreviewSession.h documents.
        assertEquals("http://test.local/m/s1.m3u8", vm.previewManifestUrl)
    }

    @Test
    fun `a channel focus arriving mid-cold-start is queued and applied once the start resolves`() {
        val startGate = CompletableDeferred<PreviewStartResponse>()
        coEvery { service.startPreview(any()) } coAnswers { startGate.await() }

        val vm = newViewModel()
        vm.selectChannel("c1")
        advancePastDebounce()
        // c1's startPreview is now suspended on startGate — a real in-flight cold start.

        vm.selectChannel("c2")
        advancePastDebounce()

        // Never a second cold start, and no switch fires until the in-flight
        // start actually resolves — c2 is queued (pendingChannelId), not
        // raced or dropped.
        coVerify(exactly = 1) { service.startPreview(any()) }
        coVerify(exactly = 0) { service.switchPreview(any(), any()) }
        assertEquals("c2", vm.focusedChannelId)

        startGate.complete(PreviewStartResponse(sessionId = "s1", manifestUrl = "/m/s1.m3u8"))
        testDispatcher.scheduler.runCurrent()

        // The queued c2 focus is applied via switchPreview() onto the session
        // that actually started (for c1) — manifest URL stays whatever the
        // cold start returned (PreviewSession.h's server-side stability
        // guarantee: switchPreview never changes it).
        coVerify(exactly = 1) { service.switchPreview("s1", match { it.channelId == "c2" }) }
        assertEquals("http://test.local/m/s1.m3u8", vm.previewManifestUrl)
    }

    @Test
    fun `multiple focuses queued during one cold start apply only the last one`() {
        val startGate = CompletableDeferred<PreviewStartResponse>()
        coEvery { service.startPreview(any()) } coAnswers { startGate.await() }

        val vm = newViewModel()
        vm.selectChannel("c1")
        advancePastDebounce()

        vm.selectChannel("c2")
        advancePastDebounce()
        vm.selectChannel("c1") // back to c1 again before the cold start ever resolves
        advancePastDebounce()

        startGate.complete(PreviewStartResponse(sessionId = "s1", manifestUrl = "/m/s1.m3u8"))
        testDispatcher.scheduler.runCurrent()

        // Only one switchPreview — for the last-queued channel — not one per
        // intermediate selection, and never a switch back onto the channel
        // the cold start itself already started for.
        coVerify(exactly = 1) { service.startPreview(any()) }
        coVerify(exactly = 0) { service.switchPreview(any(), any()) }
    }

    @Test
    fun `a failed startPreview leaves previewLoading false and no manifest url`() {
        coEvery { service.startPreview(any()) } throws RuntimeException("hephaestus unreachable")
        val vm = newViewModel()
        vm.selectChannel("c1")
        advancePastDebounce()
        assertNull(vm.previewManifestUrl)
        assertFalse(vm.previewLoading)
    }
}
