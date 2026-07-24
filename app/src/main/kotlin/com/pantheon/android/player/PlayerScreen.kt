package com.pantheon.android.player

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.NextEpisode
import kotlinx.coroutines.delay

private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)
private const val PROGRESS_PING_MS = 15_000L
private const val POSITION_POLL_MS = 500L
private const val UP_NEXT_COUNTDOWN_SECS = 10
private const val UP_NEXT_FALLBACK_WINDOW_MS = 30_000L

// Shared by both flavors, unlike Home/Library/Detail — media3-ui's
// PlayerView is a classic Android View with its own built-in D-pad-navigable
// transport controls (play/pause/seek), so there's no real Compose-material3
// vs tv-material3 split to make here; the same wrapped component works
// identically on touch and D-pad. See PlayerViewModel for the session/
// progress-reporting logic this drives.
//
// Up Next overlay mirrors hades/src/player/UpNextOverlay.tsx (card,
// countdown, Cancel) and PlayerPage.tsx's trigger/advance logic (fallback-
// window branch only — see PlayerViewModel's own comment on why). Same as
// web: reaching the actual end of the video is a second, independent
// trigger for the same advance (onEnded), a safety net in case playback
// finishes before the overlay's own window would have shown it.
@Composable
fun PlayerScreen(
    apiClient: ApiClient,
    kind: String,
    contentId: String,
    initialPositionMs: Long,
    onBack: () -> Unit,
    onAdvanceToNext: (episodeId: String) -> Unit,
) {
    val viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(apiClient, kind, contentId, initialPositionMs))
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(viewModel.manifestUrl) {
        val url = viewModel.manifestUrl ?: return@LaunchedEffect
        // No manual subtitle sideloading — the manifest itself is now a
        // multi-rendition HLS master playlist with its own AUDIO/SUBTITLES
        // #EXT-X-MEDIA groups (see hephaestus's VodSession::buildMasterPlaylist),
        // so ExoPlayer discovers every track straight from it.
        val itemBuilder = MediaItem.Builder().setUri(url)
        if (viewModel.isLive) {
            exoPlayer.setMediaItem(itemBuilder.build())
        } else {
            // VOD sessions are served as a growing HLS "event" playlist while
            // Hephaestus is still transcoding (VodSession.cpp's
            // -hls_playlist_type event) — no #EXT-X-ENDLIST until the whole
            // file finishes, and unlike live channels (paced with -re) the
            // VOD encode races ahead as fast as the hardware allows, so
            // several segments can already exist by the time this session's
            // manifest is first fetched. ExoPlayer's HLS source decides
            // "is this live" purely from ENDLIST absence, same as hls.js —
            // without an explicit start position it defaults an unprepared
            // dynamic window to the live edge, so playback jumped straight to
            // wherever the transcode had currently raced ahead to instead of
            // the actual beginning. Mirrors VideoPlayer.tsx's
            // `isLive ? {} : { startPosition: 0 }` for hls.js — this
            // manifest's own timeline always starts at 0 regardless of the
            // requested resume point (see basePositionMs's comment).
            exoPlayer.setMediaItem(itemBuilder.build(), 0L)
        }
        exoPlayer.prepare()
    }

    // Periodic watch-progress ping while playing, plus a final flush on
    // leaving — mirrors PlayerPage.tsx's PROGRESS_PING_MS interval effect,
    // including the flush-on-cleanup half (its own effect return function).
    // Gated on viewModel.durationMs (the server-known, ffprobe-derived
    // duration from VodStartResponse), not exoPlayer.duration — a
    // transcoded (non-direct-play) session's HLS playlist is "event" type
    // (see VodSession.cpp's buildVodArgs comment), which doesn't get
    // #EXT-X-ENDLIST until the *entire* transcode finishes server-side, so
    // ExoPlayer's own duration can stay unset for the whole viewing
    // session. reportProgress() already no-ops via its own durationMs
    // check when this is called too early, so no need to duplicate that
    // guard out here.
    LaunchedEffect(viewModel) {
        while (true) {
            delay(PROGRESS_PING_MS)
            viewModel.reportProgress(exoPlayer.currentPosition)
        }
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.reportProgress(exoPlayer.currentPosition)
        }
    }

    fun advance(next: NextEpisode) {
        viewModel.reportCompleted()
        onAdvanceToNext(next.episodeId)
    }

    // Drives TrackSelectionDialog below — Tracks is a live snapshot, not
    // observable on its own, so it has to be mirrored into Compose state via
    // the same onTracksChanged callback ExoPlayer already fires whenever the
    // manifest's AUDIO/SUBTITLES groups become known or the active selection
    // changes (including from our own dialog's overrides re-triggering it).
    var currentTracks by remember { mutableStateOf(Tracks.EMPTY) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                val next = viewModel.nextEpisode
                if (next != null && !viewModel.upNextDismissed) advance(next) else viewModel.reportCompleted()
            }
            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    var showTrackMenu by remember { mutableStateOf(false) }

    // Local, UI-only position polling — separate from the 15s network-ping
    // cadence above, just to drive the Up Next overlay's near-end check
    // reactively without waiting on that much coarser interval.
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition
            delay(POSITION_POLL_MS)
        }
    }

    // viewModel.durationMs, not exoPlayer.duration — see the progress-ping
    // effect's own comment above for why the latter can't be trusted for a
    // transcoded session until the whole file has finished encoding
    // server-side, which is exactly the state this overlay needs to appear
    // *before* (the last 30s of playback).
    val showUpNext by remember {
        derivedStateOf {
            val next = viewModel.nextEpisode
            next != null && !viewModel.upNextDismissed && viewModel.durationMs > 0 &&
                (viewModel.durationMs - positionMs) < UP_NEXT_FALLBACK_WINDOW_MS
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (viewModel.manifestUrl != null) {
            AndroidView(
                factory = {
                    PlayerView(context).apply {
                        player = exoPlayer
                        useController = true
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                // media3's built-in settings gear (exo_settings) drives its
                // OWN track-selection dialog straight off each manifest
                // rendition's raw Format (label/language as ExoPlayer itself
                // derives them) — in practice this collapsed every subtitle
                // entry to the same-looking row and never listed audio
                // languages at all. Rather than fight that dialog, the gear
                // stays in place (same icon, same show/hide-with-controls
                // behavior) but gets rebound to open TrackSelectionDialog
                // below instead. update runs on every recomposition, so this
                // stays bound even though the controller view is inflated
                // lazily and may not exist yet on the first pass.
                update = { view ->
                    view.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
                        ?.setOnClickListener { showTrackMenu = true }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (viewModel.loading) {
            CircularProgressIndicator(color = GoldColor, modifier = Modifier.align(Alignment.Center))
        }
        viewModel.errorMessage?.let { message ->
            Text(message, color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        if (showUpNext) {
            val next = viewModel.nextEpisode!!
            UpNextOverlay(
                apiClient = apiClient,
                nextEpisode = next,
                onPlayNow = { advance(next) },
                onDismiss = { viewModel.dismissUpNext() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            )
        }

        if (showTrackMenu) {
            TrackSelectionDialog(
                exoPlayer = exoPlayer,
                tracks = currentTracks,
                onDismiss = { showTrackMenu = false },
            )
        }
    }

    BackHandler(onBack = onBack)
}

@Composable
private fun UpNextOverlay(
    apiClient: ApiClient,
    nextEpisode: NextEpisode,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var secsLeft by remember { mutableStateOf(UP_NEXT_COUNTDOWN_SECS) }
    val cancelFocusRequester = remember { FocusRequester() }

    // Defaults focus to Cancel the moment the overlay appears, so a D-pad's
    // OK button stops auto-play by default rather than requiring an
    // explicit nav over from the transport controls first — same reasoning
    // as UpNextOverlay.tsx's own setFocus(CANCEL_FOCUS_KEY) effect.
    LaunchedEffect(Unit) { cancelFocusRequester.requestFocus() }

    LaunchedEffect(secsLeft) {
        if (secsLeft <= 0) { onPlayNow(); return@LaunchedEffect }
        delay(1000)
        secsLeft -= 1
    }

    Column(
        modifier = modifier
            .width(340.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xEE1B1C29))
            .clickable(onClick = onPlayNow)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = apiClient.mediaUrl("/api/episodes/${nextEpisode.episodeId}/thumb"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(96.dp).aspectRatio(16f / 9f).background(TileBg),
            )
            Column(modifier = Modifier.padding(start = 12.dp).fillMaxWidth()) {
                Text("Up Next", color = TextDim, style = MaterialTheme.typography.labelSmall)
                Text("S${nextEpisode.season} · E${nextEpisode.episode}", color = TextDim, style = MaterialTheme.typography.bodySmall)
                Text(
                    nextEpisode.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("Playing Next In: ${secsLeft}s", color = TextDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
        TextButton(
            onClick = { onDismiss() },
            modifier = Modifier.focusRequester(cancelFocusRequester),
        ) { Text("Cancel", color = GoldColor) }
        LinearProgressIndicator(
            progress = { 1f - (secsLeft.toFloat() / UP_NEXT_COUNTDOWN_SECS) },
            modifier = Modifier.fillMaxWidth(),
            color = GoldColor,
            trackColor = TileBg,
        )
    }
}

// Replaces media3's own settings-gear track dialog (see PlayerScreen's
// AndroidView update block for why) — built straight off ExoPlayer's live
// Tracks/Format objects rather than re-deriving Pantheon's own index scheme
// the way Hades has to (see hades/src/player/VideoPlayer.tsx's
// X-PANTHEON-INDEX comment). media3's HLS parser doesn't expose arbitrary
// #EXT-X-MEDIA "X-" attributes the way hls.js does, but it doesn't need to
// here: Format.label/language (from the manifest's own NAME/LANGUAGE) are
// already distinct per rendition, and TrackSelectionOverride targets the
// real TrackGroup object directly instead of a numeric index translation.
@Composable
private fun TrackSelectionDialog(
    exoPlayer: ExoPlayer,
    tracks: Tracks,
    onDismiss: () -> Unit,
) {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val subtitlesOff = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

    fun selectAudio(group: Tracks.Group, index: Int) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    fun selectSubtitle(group: Tracks.Group, index: Int) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    fun disableSubtitles() {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun trackLabel(format: androidx.media3.common.Format, fallbackIndex: Int, kind: String): String {
        format.label?.let { if (it.isNotBlank()) return it }
        format.language?.let { if (it.isNotBlank()) return it.uppercase() }
        return "$kind $fallbackIndex"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .heightIn(max = 480.dp)
                .padding(24.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xEE1B1C29))
                .clickable(onClick = {}) // swallow taps so they don't fall through to the scrim's dismiss above
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Audio", color = TextDim, style = MaterialTheme.typography.labelSmall)
            if (audioGroups.isEmpty()) {
                Text(
                    "No audio tracks found", color = TextDim, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
            }
            audioGroups.forEachIndexed { groupIdx, group ->
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val selected = group.isTrackSelected(i)
                    Text(
                        trackLabel(format, groupIdx, "Audio"),
                        color = if (selected) GoldColor else Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectAudio(group, i) }
                            .padding(vertical = 8.dp),
                    )
                }
            }

            Text(
                "Subtitles", color = TextDim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Off",
                color = if (subtitlesOff) GoldColor else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { disableSubtitles() }
                    .padding(vertical = 8.dp),
            )
            textGroups.forEachIndexed { groupIdx, group ->
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val selected = !subtitlesOff && group.isTrackSelected(i)
                    Text(
                        trackLabel(format, groupIdx, "Subtitle"),
                        color = if (selected) GoldColor else Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectSubtitle(group, i) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}
