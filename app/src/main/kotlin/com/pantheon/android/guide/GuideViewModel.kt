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
// Small lookback/forward window — unlike hades/src/guide/useGuideSession.ts
// this screen only ever shows "what's on now" per channel (a channel list,
// not a full multi-hour time grid — see GuideScreen.kt's own comment for
// why), so there's no need for WINDOW_FORWARD_HOURS-sized fetches.
private const val EPG_LOOKBACK_MIN = 5
private const val EPG_WINDOW_HOURS = 1

// Kotlin counterpart of hades/src/guide/useGuideSession.ts, scoped to a
// channel-list Guide (see GuideScreen.kt). Same live-preview session
// lifecycle guards (single in-flight start, debounced selection, generation
// counter to discard a stale start once superseded) — the
// document.visibilitychange handling doesn't have a direct Android
// equivalent in this pass; onCleared() below covers navigating away, which
// is this app's actual equivalent of "the tab closed."
class GuideViewModel(private val apiClient: ApiClient) : ViewModel() {

    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var currentProgramByChannel by mutableStateOf<Map<String, EpgProgram?>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var selectedChannelId by mutableStateOf<String?>(null)
        private set
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
    }

    private fun load() {
        viewModelScope.launch {
            loading = true
            errorMessage = null
            try {
                val chs = apiClient.service.getChannels().sortedBy { it.number }
                channels = chs
                val fromSec = (System.currentTimeMillis() / 1000) - EPG_LOOKBACK_MIN * 60
                val nowMs = System.currentTimeMillis()
                val programs = coroutineScope {
                    chs.map { ch ->
                        async {
                            val list = runCatching { apiClient.service.getChannelEpg(ch.channelId, EPG_WINDOW_HOURS, fromSec) }.getOrDefault(emptyList())
                            ch.channelId to list.find { it.wallClockStartMs <= nowMs && nowMs < it.wallClockEndMs }
                        }
                    }.awaitAll()
                }
                currentProgramByChannel = programs.toMap()
            } catch (e: Exception) {
                errorMessage = "Couldn't load Guide: ${e.message ?: "unknown error"}"
            } finally {
                loading = false
            }
        }
    }

    // Debounced the same way useGuideSession's focus handler is (rapid
    // D-pad traversal across many channel rows shouldn't spin up an encode
    // session per row landed on) — selecting the same channel twice is a
    // cheap no-op check, not re-debounced.
    fun selectChannel(channelId: String) {
        if (selectedChannelId == channelId) return
        selectedChannelId = channelId
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
