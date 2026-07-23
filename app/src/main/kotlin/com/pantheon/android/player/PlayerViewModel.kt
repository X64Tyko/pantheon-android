package com.pantheon.android.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pantheon.android.BuildConfig
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.NextEpisode
import com.pantheon.android.api.dto.VodStartRequest
import com.pantheon.android.api.dto.VodTracks
import com.pantheon.android.api.dto.WatchProgressBody
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// "android-tv" vs "android-mobile" — this file is shared by both formFactor
// flavors (see build.gradle.kts's flavorDimensions), so BuildConfig.FLAVOR
// (e.g. "googleTv"/"amazonMobile") is the only thing distinguishing them at
// runtime. Feeds Kairos's local play-history table (device_type column),
// nothing client-side reads it back.
private val deviceType: String =
    if (BuildConfig.FLAVOR.contains("Tv", ignoreCase = true)) "android-tv" else "android-mobile"

// Kotlin counterpart of hades/src/player/usePlaybackSession.ts, including its
// reload()-based track-switch: a track switch and a seek are the same
// operation server-side ("stop this VOD session, start a fresh one"), so
// selectTracks() below just calls the same load() a seek would.
// Resume position, live-vs-VOD session lifecycle, periodic watch-progress
// reporting, and Up Next auto-advance are the other behaviors this needs to
// get right, and does.
//
// Deliberately no chapter-based intro/credits detection the way
// PlayerPage.tsx's showUpNext also can trigger on (there's no chapters
// fetch built on this flavor at all) — this always uses that logic's
// fallback branch: the last UP_NEXT_FALLBACK_WINDOW_MS of the episode,
// same window PlayerPage.tsx falls back to for any episode with no chapter
// data. Same end-user experience for the common case, without needing the
// whole chapters/credits-chapter-type infrastructure Android has no other
// use for yet.
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
    var nextEpisode by mutableStateOf<NextEpisode?>(null)
        private set
    var upNextDismissed by mutableStateOf(false)
        private set
    var tracks by mutableStateOf<VodTracks?>(null)
        private set
    // -1 for both: audioTrack means "server picked the first/default track"
    // (resolved to a real index once the session responds), subtitleTrack
    // means "off" — same convention as usePlaybackSession.ts's own state.
    var audioTrack by mutableStateOf(-1)
        private set
    var subtitleTrack by mutableStateOf(-1)
        private set
    var directPlay by mutableStateOf(false)
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
        load(initialPositionMs, -1, -1)
    }

    private fun load(positionMs: Long, aTrack: Int, sTrack: Int) {
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
                apiClient.service.startVodPlayback(
                    VodStartRequest(
                        contentType = kind,
                        contentId = contentId,
                        // -1 is "unset"/"off" on our side; the server's own
                        // default for an omitted field is the same -1 (see
                        // Router.cpp's `body.value("audio_track", -1)`), so
                        // null and -1 are equivalent here — sent as null
                        // purely to match usePlaybackSession.ts's `?? undefined`.
                        audioTrack = aTrack.takeIf { it >= 0 },
                        subtitleTrack = sTrack.takeIf { it != -1 },
                        positionMs = positionMs,
                    ),
                )
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
                tracks = res.tracks
                directPlay = res.directPlay
                audioTrack = if (aTrack >= 0) aTrack else (res.tracks?.audio?.firstOrNull()?.index ?: -1)
                subtitleTrack = sTrack
            }.onFailure { e ->
                errorMessage = e.message ?: "Failed to start playback"
            }
            loading = false

            // Fire-and-forget, doesn't gate loading=false above on it — a
            // slow/failed next-episode lookup shouldn't delay playback
            // actually starting, it just means no Up Next overlay this time.
            if (kind == "episode") {
                nextEpisode = runCatching { apiClient.service.getNextEpisode(contentId) }.getOrNull()
            }
        }
    }

    fun dismissUpNext() { upNextDismissed = true }

    // VOD only — restarts the session at the given absolute position with a
    // new track selection. currentPositionMs is the player's own (manifest-
    // relative) position; basePositionMs converts it to the absolute
    // position a fresh session needs, same as usePlaybackSession.ts's
    // reload(). null (not -1) means "leave this track as-is" — -1 is itself
    // a real, explicit subtitle selection ("off"), so it can't double as the
    // sentinel the way it does for audioTrack's "server picks the default"
    // meaning elsewhere in this class.
    fun selectTracks(currentPositionMs: Long, newAudioTrack: Int? = null, newSubtitleTrack: Int? = null) {
        if (isLive) return
        load(basePositionMs + currentPositionMs, newAudioTrack ?: audioTrack, newSubtitleTrack ?: subtitleTrack)
    }

    // Called every PROGRESS_PING_MS by the player screen's own timer, fed
    // the player's raw (manifest-relative) position — mirrors PlayerPage.tsx's
    // interval ping exactly, same 0-guard against a not-yet-started player.
    fun reportProgress(playerPositionMs: Long) {
        if (isLive || durationMs <= 0) return
        val absolute = basePositionMs + playerPositionMs
        if (absolute <= 0) return
        viewModelScope.launch { runCatching {
            apiClient.service.putWatchProgress(kind, contentId, WatchProgressBody(absolute, durationMs, deviceType = deviceType, directPlay = directPlay))
        } }
    }

    // Natural end-of-content — explicit completed=true, same as
    // PlayerPage.tsx's handleNaturalEnd/handleAdvanceToNext.
    fun reportCompleted() {
        if (isLive || durationMs <= 0) return
        viewModelScope.launch { runCatching {
            apiClient.service.putWatchProgress(kind, contentId, WatchProgressBody(durationMs, durationMs, completed = true, deviceType = deviceType, directPlay = directPlay))
        } }
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
