package com.pantheon.android.guide

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

// Small muted, controls-less live preview — shared by both flavors' Guide
// screens (same rationale as PlayerScreen.kt: PlayerView is flavor-agnostic,
// no material3-vs-tv-material3 split needed for a raw video surface). One
// ExoPlayer instance per GuideScreen mount, retargeted via setMediaItem()
// as the channel changes rather than recreated — recreating the whole
// AndroidView/ExoPlayer instance on every channel switch would reintroduce
// exactly the kind of janky rebuild this whole manifest-driven project has
// otherwise avoided.
//
// reloadKey should be the focused channel's id — the same signal
// ChannelHeaderCell's own focus highlight reacts to instantly. It's a
// separate key from manifestUrl on purpose: PreviewSession.h's own comment
// ("Manifest URL is stable for the session's whole life — switchChannel()
// ... reused by every later switchChannel()") means manifestUrl stays the
// exact same string across every channel switch after the first, so keying
// this effect on manifestUrl alone means it only ever fires once — ExoPlayer
// never gets told to reload after that, and is left to notice on its own
// that the server deleted and recreated the segments underneath it (fragile:
// a live HLS media-sequence reset like that isn't something players reliably
// self-recover from). Keying on the channel id too forces a real reload
// every time the focused channel actually changes, even though the URL
// itself never does.
@Composable
fun PreviewPlayerView(manifestUrl: String?, reloadKey: Any? = manifestUrl, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            volume = 0f
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }
    LaunchedEffect(manifestUrl, reloadKey) {
        if (manifestUrl == null) { exoPlayer.stop(); return@LaunchedEffect }
        exoPlayer.setMediaItem(MediaItem.fromUri(manifestUrl))
        exoPlayer.prepare()
    }

    AndroidView(
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = false
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = modifier.background(Color.Black),
    )
}
