package com.pantheon.android.guide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.Channel
import com.pantheon.android.api.dto.EpgProgram
import com.pantheon.android.api.dto.PreviewStartRequest
import com.pantheon.android.api.dto.PreviewSwitchRequest
import com.pantheon.android.api.dto.TvZone
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SELECT_DEBOUNCE_MS = 300L
private const val CLOCK_TICK_MS = 30_000L

// Matches hades/src/guide/constants.ts's WINDOW_LOOKBACK_MIN/
// WINDOW_FORWARD_HOURS exactly — a real multi-hour grid (GuideScreen.kt, both
// flavors) needs the same window web's does, not the old channel-list
// scoping's "just what's on now" 1hr fetch. EPG_WINDOW_HOURS is rounded up
// from lookback+forward (0.5h + 4h = 4.5h) because Kairos' /hours query param
// is parsed with std::stoi (SchedulerService.cpp), which truncates a
// fractional value rather than rounding it — 5 guarantees the fetch still
// covers the full window that math implies.
const val WINDOW_LOOKBACK_MIN = 30
const val WINDOW_FORWARD_HOURS = 4
private const val EPG_WINDOW_HOURS = 5

// Kotlin counterpart of hades/src/guide/useGuideSession.ts. Same live-preview
// session lifecycle guards (single in-flight start, debounced selection,
// generation counter to discard a stale start once superseded) — the
// document.visibilitychange handling doesn't have a direct Android
// equivalent in this pass; onCleared() below covers navigating away, which
// is this app's actual equivalent of "the tab closed."
//
// focusedProgram is independent of focusedChannelId/selectChannel's preview
// session: it only ever drives the preview hero's TEXT (title/countdown).
// The live video always follows the focused *channel* alone — you can't
// actually preview a future program, only read about it — see
// beginPreview()'s own doc and selectProgram() below.
class GuideViewModel(private val apiClient: ApiClient) : ViewModel() {

    // guide.zones per GET /api/tv/manifest: channel-header/time-grid/
    // preview-panel — same structural-presence gating as
    // hades/src/tv/TvGuideSection.tsx's useZoneManifest('guide').
    var zones by mutableStateOf<List<TvZone>>(emptyList())
        private set
    fun hasZone(id: String) = zones.any { it.id == id }

    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    // Every fetched program per channel across the full window, not just
    // "what's on now" — the grid renders every block in this list, not a
    // single current-program lookup.
    var epgByChannel by mutableStateOf<Map<String, List<EpgProgram>>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Fixed at load time, same as useGuideSession.ts's windowStartMs ref —
    // the grid's time axis doesn't slide as the clock ticks, only nowMs
    // (the "now" marker's position within it) does.
    val windowStartMs: Long = System.currentTimeMillis() - WINDOW_LOOKBACK_MIN * 60_000L
    var nowMs by mutableStateOf(System.currentTimeMillis())
        private set

    var focusedChannelId by mutableStateOf<String?>(null)
        private set
    var focusedProgram by mutableStateOf<EpgProgram?>(null)
        private set

    val focusedChannel: Channel? get() = channels.find { it.channelId == focusedChannelId }
    val nowProgram: EpgProgram?
        get() = focusedChannelId?.let { id ->
            epgByChannel[id]?.find { it.wallClockStartMs <= nowMs && nowMs < it.wallClockEndMs }
        }

    var previewManifestUrl by mutableStateOf<String?>(null)
        private set
    var previewLoading by mutableStateOf(false)
        private set

    private var sessionId: String? = null
    private var startingJob: Job? = null
    private var selectJob: Job? = null
    private var generation = 0

    init {
        load()
        tickClock()
    }

    private fun tickClock() {
        viewModelScope.launch {
            while (true) {
                delay(CLOCK_TICK_MS)
                nowMs = System.currentTimeMillis()
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            loading = true
            errorMessage = null
            try {
                val manifest = runCatching { apiClient.service.getTvManifest() }.getOrNull()
                zones = manifest?.guide?.zones?.sortedBy { it.order } ?: emptyList()

                val chs = apiClient.service.getChannels().sortedBy { it.number }
                channels = chs
                val fromSec = windowStartMs / 1000
                epgByChannel = coroutineScope {
                    chs.map { ch ->
                        async {
                            ch.channelId to runCatching { apiClient.service.getChannelEpg(ch.channelId, EPG_WINDOW_HOURS, fromSec) }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }.toMap()
            } catch (e: Exception) {
                errorMessage = "Couldn't load Guide: ${e.message ?: "unknown error"}"
            } finally {
                loading = false
            }
        }
    }

    // Channel-header focus (or a plain channel switch) — "nothing specific,"
    // fall back to that channel's own live program in the preview hero.
    // Debounced the same way useGuideSession's focus handler is (rapid
    // D-pad traversal across many channels shouldn't spin up an encode
    // session per column landed on) — selecting the same channel twice is a
    // cheap no-op check, not re-debounced.
    fun selectChannel(channelId: String) {
        focusedProgram = null
        if (focusedChannelId == channelId) return
        focusedChannelId = channelId
        selectJob?.cancel()
        selectJob = viewModelScope.launch {
            delay(SELECT_DEBOUNCE_MS)
            beginPreview(channelId)
        }
    }

    // A specific program cell (now OR future) was focused — still switches
    // the live preview to that channel (same debounce as selectChannel), but
    // also pins the hero's text to this exact program rather than whatever's
    // live right now.
    fun selectProgram(channelId: String, program: EpgProgram) {
        focusedProgram = program
        if (focusedChannelId == channelId) return
        focusedChannelId = channelId
        selectJob?.cancel()
        selectJob = viewModelScope.launch {
            delay(SELECT_DEBOUNCE_MS)
            beginPreview(channelId)
        }
    }

    private fun beginPreview(channelId: String) {
        val myGen = generation
        val currentSession = sessionId
        if (currentSession != null) {
            viewModelScope.launch { runCatching { apiClient.service.switchPreview(currentSession, PreviewSwitchRequest(channelId)) } }
            return
        }
        if (startingJob?.isActive == true) return // one start in flight; the debounce above already coalesces rapid re-selection
        previewLoading = true
        startingJob = viewModelScope.launch {
            val result = runCatching { apiClient.service.startPreview(PreviewStartRequest(channelId)) }
            if (generation != myGen) {
                // Superseded (screen left / another selection raced ahead) — stop what we just started rather than leak it.
                result.getOrNull()?.let { res -> runCatching { apiClient.service.stopPreview(res.sessionId) } }
                return@launch
            }
            result.onSuccess { res ->
                sessionId = res.sessionId
                previewManifestUrl = apiClient.streamUrl(res.manifestUrl)
            }
            previewLoading = false
        }
    }

    // GlobalScope is deliberate here, not an oversight: viewModelScope is
    // cancelled as part of onCleared() itself, so a call launched on it from
    // inside onCleared() races its own cancellation and may never actually
    // reach the network — this fire-and-forget stop (mirrors previewApi.ts's
    // stopPreview(), which is a plain uncancellable fetch()) needs to
    // survive past this ViewModel's own lifecycle to reliably tear down the
    // encode session server-side.
    @OptIn(DelicateCoroutinesApi::class)
    private fun stopCurrentPreview() {
        generation++ // invalidates any in-flight start so it self-stops on resolve
        sessionId?.let { sid -> GlobalScope.launch(Dispatchers.IO) { runCatching { apiClient.service.stopPreview(sid) } } }
        sessionId = null
        previewManifestUrl = null
        previewLoading = false
    }

    override fun onCleared() {
        stopCurrentPreview()
    }

    companion object {
        fun factory(apiClient: ApiClient) = viewModelFactory {
            initializer { GuideViewModel(apiClient) }
        }
    }
}
