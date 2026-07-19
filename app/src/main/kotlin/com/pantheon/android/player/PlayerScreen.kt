package com.pantheon.android.player

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
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
        val itemBuilder = MediaItem.Builder().setUri(url)
        // subtitle_url is only ever populated for extractable (WebVTT
        // sidecar) tracks — a burned-in track is already composited into
        // the video stream server-side and never populates it, so presence
        // alone is the correct condition (see VodStartResponse's comment).
        viewModel.subtitleUrl?.let { subUrl ->
            itemBuilder.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subUrl))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage("en")
                    .build(),
            ))
        }
        exoPlayer.setMediaItem(itemBuilder.build())
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

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                val next = viewModel.nextEpisode
                if (next != null && !viewModel.upNextDismissed) advance(next) else viewModel.reportCompleted()
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

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
