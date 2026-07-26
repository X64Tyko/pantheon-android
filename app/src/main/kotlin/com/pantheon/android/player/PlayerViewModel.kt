package com.pantheon.android.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.gson.Gson
import com.pantheon.android.BuildConfig
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.NextEpisode
import com.pantheon.android.api.dto.VodStartRequest
import com.pantheon.android.api.dto.WatchProgressBody
import com.pantheon.android.api.dto.WatchTogetherCommandBody
import com.pantheon.android.api.dto.WatchTogetherEvent
import com.pantheon.android.api.dto.WatchTogetherHeartbeatBody
import com.pantheon.android.api.dto.WatchTogetherSession
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

// "android-tv" vs "android-mobile" — this file is shared by both formFactor
// flavors (see build.gradle.kts's flavorDimensions), so BuildConfig.FLAVOR
// (e.g. "googleTv"/"amazonMobile") is the only thing distinguishing them at
// runtime. Feeds Kairos's local play-history table (device_type column),
// nothing client-side reads it back.
private val deviceType: String =
    if (BuildConfig.FLAVOR.contains("Tv", ignoreCase = true)) "android-tv" else "android-mobile"

// Kotlin counterpart of hades/src/player/usePlaybackSession.ts. Unlike the
// web client, this app has no app-level track-switch API at all — the VOD
// manifest Hephaestus serves is now a real multi-rendition HLS master
// playlist (AUDIO/SUBTITLES groups — see hephaestus/src/stream/VodSession.cpp's
// buildMasterPlaylist), so ExoPlayer's own TrackSelector/native settings menu
// drives audio and subtitle selection directly against the manifest; nothing
// here needs to restart the session for it (see PlayerScreen's exo_settings
// gear, left visible instead of hidden).
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
    private val wtSessionId: String?,
) : ViewModel() {

    var loading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var manifestUrl by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var nextEpisode by mutableStateOf<NextEpisode?>(null)
        private set
    var upNextDismissed by mutableStateOf(false)
        private set
    var directStream by mutableStateOf(false)
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

    // ── Watch Together ───────────────────────────────────────────────────────
    // Session identity/host is Kairos-owned (WatchTogetherService.cpp) — this
    // one round trip decides host vs. follower for everything below, mirroring
    // PlayerPage.tsx's own getWatchTogether effect. wtActive (not just
    // wtSessionId != null) gates every WT behavior below so an explicit
    // "Leave" click can turn all of it off without needing to re-navigate —
    // see leaveWatchTogether().
    var wtActive by mutableStateOf(wtSessionId != null)
        private set
    var wtSession by mutableStateOf<WatchTogetherSession?>(null)
        private set
    val isWtHost: Boolean
        get() = wtActive && wtSession != null && apiClient.currentUserId != null && apiClient.currentUserId == wtSession?.hostUserId
    val isWtFollower: Boolean
        get() = wtActive && wtSession != null && !isWtHost

    // One-shot events off Hermes' SSE stream, for PlayerScreen (which owns
    // the actual ExoPlayer instance) to apply — a Flow rather than plain
    // mutableState so two back-to-back events with identical field values
    // (e.g. two `sync` ticks with the same paused flag) both still reach the
    // collector instead of one getting deduped away by Compose snapshot
    // equality.
    private val _wtEvents = MutableSharedFlow<WatchTogetherEvent>(extraBufferCapacity = 4)
    val wtEvents: SharedFlow<WatchTogetherEvent> = _wtEvents

    private var wtEventSource: EventSource? = null
    private val gson = Gson()

    init {
        load(initialPositionMs)
        if (wtSessionId != null) loadWtSession(wtSessionId)
    }

    private fun loadWtSession(id: String) {
        viewModelScope.launch {
            wtSession = runCatching { apiClient.service.getWatchTogether(id) }.getOrNull()
            if (wtSession != null && !isWtHost) subscribeWtFollower(id)
        }
    }

    // EventSourceListener callbacks land on OkHttp's own dispatcher thread,
    // not the main/viewModelScope one — _wtEvents.tryEmit is safe to call
    // from any thread (MutableSharedFlow's contract), so no dispatcher hop
    // is needed just to publish the parsed event.
    private fun subscribeWtFollower(id: String) {
        wtEventSource?.cancel()
        wtEventSource = apiClient.openWatchTogetherStream(id, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { gson.fromJson(data, WatchTogetherEvent::class.java) }
                    .getOrNull()?.let { _wtEvents.tryEmit(it) }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // okhttp-sse retries on its own (same reconnect-by-default
                // behavior as a browser's EventSource) — nothing to do here
                // beyond letting that happen; a dropped tick just means the
                // next successful one corrects position/paused as normal.
            }
        })
    }

    // Host-only, fire-and-forget — cheap/frequent, updates Hermes'
    // extrapolation baseline between explicit seek/pause/play commands. See
    // PlayerScreen's own WT_HEARTBEAT_MS loop, which is what actually calls
    // this on a timer (this ViewModel doesn't own the player, so it can't
    // poll position itself).
    fun sendWtHeartbeat(positionMs: Long, paused: Boolean) {
        val id = wtSessionId ?: return
        if (!isWtHost) return
        viewModelScope.launch {
            runCatching { apiClient.service.postWatchTogetherHeartbeat(id, WatchTogetherHeartbeatBody(positionMs, paused)) }
        }
    }

    // Host-only explicit action — becomes the new authoritative baseline on
    // Hermes immediately (not just on the next heartbeat tick) and is what
    // followers apply right away. PlayerScreen calls this from its own
    // ExoPlayer listeners (play/pause state changes, real seeks).
    fun sendWtCommand(type: String, positionMs: Long? = null) {
        val id = wtSessionId ?: return
        if (!isWtHost) return
        viewModelScope.launch {
            runCatching { apiClient.service.postWatchTogetherCommand(id, WatchTogetherCommandBody(type, positionMs)) }
        }
    }

    // Explicit "Leave"/"End" control — mirrors PlayerPage.tsx's
    // handleLeaveWatchTogether: stop participating but stay in the player,
    // watching solo, rather than navigating away. Best-effort — the same
    // host-closes/follower-leaves split as the automatic onCleared cleanup
    // below, just triggered immediately instead of on teardown.
    fun leaveWatchTogether() {
        val id = wtSessionId ?: return
        val wasHost = isWtHost
        wtActive = false
        wtEventSource?.cancel()
        wtEventSource = null
        viewModelScope.launch {
            runCatching {
                if (wasHost) apiClient.service.closeWatchTogether(id) else apiClient.service.leaveWatchTogether(id)
            }
        }
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
                apiClient.service.startVodPlayback(
                    // audioTrack/subtitleTrack left unset — the server
                    // auto-picks (saved per-show preference if any, else the
                    // first/default track; see VodSession::start()) and
                    // ExoPlayer's own TrackSelector can switch away from that
                    // against the master manifest without this app needing
                    // to know or ask for a specific one up front.
                    VodStartRequest(
                        contentType = kind,
                        contentId = contentId,
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
                title = res.title
                durationMs = res.durationMs
                directStream = res.directStream
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

    // Called every PROGRESS_PING_MS by the player screen's own timer, fed
    // the player's raw (manifest-relative) position — mirrors PlayerPage.tsx's
    // interval ping exactly, same 0-guard against a not-yet-started player.
    fun reportProgress(playerPositionMs: Long) {
        if (isLive || durationMs <= 0) return
        val absolute = basePositionMs + playerPositionMs
        if (absolute <= 0) return
        viewModelScope.launch { runCatching {
            apiClient.service.putWatchProgress(kind, contentId, WatchProgressBody(absolute, durationMs, deviceType = deviceType, directStream = directStream))
        } }
    }

    // Natural end-of-content — explicit completed=true, same as
    // PlayerPage.tsx's handleNaturalEnd/handleAdvanceToNext.
    fun reportCompleted() {
        if (isLive || durationMs <= 0) return
        viewModelScope.launch { runCatching {
            apiClient.service.putWatchProgress(kind, contentId, WatchProgressBody(durationMs, durationMs, completed = true, deviceType = deviceType, directStream = directStream))
        } }
    }

    // GlobalScope deliberate — see GuideViewModel.stopCurrentPreview()'s
    // identical comment: viewModelScope is cancelled as part of onCleared()
    // itself, so a call launched on it here would race its own cancellation.
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        generation++
        wtEventSource?.cancel()
        if (wtActive) {
            val id = wtSessionId
            val wasHost = isWtHost
            if (id != null) {
                GlobalScope.launch(Dispatchers.IO) { runCatching {
                    if (wasHost) apiClient.service.closeWatchTogether(id) else apiClient.service.leaveWatchTogether(id)
                } }
            }
        }
        val sid = sessionId ?: return
        GlobalScope.launch(Dispatchers.IO) { runCatching { apiClient.service.stopVodPlayback(sid) } }
    }

    companion object {
        fun factory(apiClient: ApiClient, kind: String, contentId: String, initialPositionMs: Long, wtSessionId: String? = null) = viewModelFactory {
            initializer { PlayerViewModel(apiClient, kind, contentId, initialPositionMs, wtSessionId) }
        }
    }
}