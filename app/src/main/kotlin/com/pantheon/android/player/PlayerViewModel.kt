package com.pantheon.android.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.VodStartRequest
import com.pantheon.android.api.dto.WatchProgressBody
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// Kotlin counterpart of hades/src/player/usePlaybackSession.ts, trimmed to
// this pass's scope: no track-switch/reload (audio/subtitle track picker is
// a real follow-up, not built this round — media3-ui's PlayerView still
// exposes whatever the manifest itself carries as embedded/default tracks),
// no Up-Next auto-advance. Resume position, live-vs-VOD session lifecycle,
// and periodic watch-progress reporting are the real behaviors this needs
// to get right, and does.
class PlayerViewModel(
    private val apiClient: ApiClient,
    private val kind: String, // "movie" | "episode" | "channel"
    private val contentId: String,
    initialPositionMs: Long,
) : ViewModel() {

    var loading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var manifestUrl by mutableStateOf<String?>(null)
        private set
    var subtitleUrl by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var durationMs by mutableStateOf(0L)
        private set

    val isLive: Boolean get() = kind == "channel"

    // The absolute (source-file) position this session's manifest t=0
    // represents — see usePlaybackSession.ts's identical field for why this
    // has to be tracked separately from the player's own currentPosition
    // (always 0-based per manifest): a VOD encode always starts its HLS
    // timeline at 0 regardless of the requested start position.
    var basePositionMs: Long = initialPositionMs
        private set

    private var sessionId: String? = null
    private var generation = 0

    init {
        load(initialPositionMs)
    }

    private fun load(positionMs: Long) {
        val myGen = ++generation
        val prevSession = sessionId
        sessionId = null
        basePositionMs = positionMs

        viewModelScope.launch {
            prevSession?.let { runCatching { apiClient.service.stopVodPlayback(it) } }

            if (isLive) {
                loading = true
                errorMessage = null
                val access = runCatching { apiClient.service.checkChannelAccess(contentId) }.getOrNull()
                if (generation != myGen) return@launch
                if (access?.allowed != true) {
                    errorMessage = "This channel is restricted on your account."
                    loading = false
                    return@launch
                }
                manifestUrl = apiClient.liveChannelManifestUrl(contentId)
                loading = false
                return@launch
            }

            loading = true
            errorMessage = null
            val result = runCatching {
                apiClient.service.startVodPlayback(VodStartRequest(contentType = kind, contentId = contentId, positionMs = positionMs))
            }
            if (generation != myGen) {
                result.getOrNull()?.let { res -> runCatching { apiClient.service.stopVodPlayback(res.sessionId) } }
                return@launch
            }
            result.onSuccess { res ->
                sessionId = res.sessionId
                manifestUrl = apiClient.streamUrl(res.manifestUrl)
                subtitleUrl = res.subtitleUrl?.let { apiClient.streamUrl(it) }
                title = res.title
                durationMs = res.durationMs
            }.onFailure { e ->
                errorMessage = e.message ?: "Failed to start playback"
            }
            loading = false
        }
    }

    // Called every PROGRESS_PING_MS by the player screen's own timer, fed
    // the player's raw (manifest-relative) position — mirrors PlayerPage.tsx's
    // interval ping exactly, same 0-guard against a not-yet-started player.
    fun reportProgress(playerPositionMs: Long) {
        if (isLive || durationMs <= 0) return
        val absolute = basePositionMs + playerPositionMs
        if (absolute <= 0) return
        viewModelScope.launch { runCatching { apiClient.service.putWatchProgress(kind, contentId, WatchProgressBody(absolute, durationMs)) } }
    }

    // Natural end-of-content — explicit completed=true, same as
    // PlayerPage.tsx's handleNaturalEnd/handleAdvanceToNext.
    fun reportCompleted() {
        if (isLive || durationMs <= 0) return
        viewModelScope.launch { runCatching { apiClient.service.putWatchProgress(kind, contentId, WatchProgressBody(durationMs, durationMs, completed = true)) } }
    }

    // GlobalScope deliberate — see GuideViewModel.stopCurrentPreview()'s
    // identical comment: viewModelScope is cancelled as part of onCleared()
    // itself, so a call launched on it here would race its own cancellation.
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        generation++
        val sid = sessionId ?: return
        GlobalScope.launch(Dispatchers.IO) { runCatching { apiClient.service.stopVodPlayback(sid) } }
    }

    companion object {
        fun factory(apiClient: ApiClient, kind: String, contentId: String, initialPositionMs: Long) = viewModelFactory {
            initializer { PlayerViewModel(apiClient, kind, contentId, initialPositionMs) }
        }
    }
}
