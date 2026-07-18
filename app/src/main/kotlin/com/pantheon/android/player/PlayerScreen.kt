package com.pantheon.android.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pantheon.android.api.ApiClient
import kotlinx.coroutines.delay

private val GoldColor = Color(0xFFE0B84E)
private const val PROGRESS_PING_MS = 15_000L

// Shared by both flavors, unlike Home/Library/Detail — media3-ui's
// PlayerView is a classic Android View with its own built-in D-pad-navigable
// transport controls (play/pause/seek), so there's no real Compose-material3
// vs tv-material3 split to make here; the same wrapped component works
// identically on touch and D-pad. See PlayerViewModel for the session/
// progress-reporting logic this drives.
@Composable
fun PlayerScreen(
    apiClient: ApiClient,
    kind: String,
    contentId: String,
    initialPositionMs: Long,
    onBack: () -> Unit,
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
    LaunchedEffect(viewModel) {
        while (true) {
            delay(PROGRESS_PING_MS)
            if (exoPlayer.duration > 0) viewModel.reportProgress(exoPlayer.currentPosition)
        }
    }
    DisposableEffect(viewModel) {
        onDispose {
            if (exoPlayer.duration > 0) viewModel.reportProgress(exoPlayer.currentPosition)
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) viewModel.reportCompleted()
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
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
    }

    BackHandler(onBack = onBack)
}
