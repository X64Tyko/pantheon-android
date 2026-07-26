package com.pantheon.android.player

import com.pantheon.android.api.dto.WatchTogetherEvent
import kotlin.math.abs

// Watch Together: how far a follower's local position must drift from a
// periodic `sync` tick's authoritative one before snapping to it — small
// clock/decode jitter shouldn't cause constant micro-seeking, but a stalled
// rebuffer or a fresh join should correct promptly. Mirrors hades/src/player/
// wtEventEffect.ts's identical constant (kept in sync manually — that module
// only owns the decision logic, not the ExoPlayer/Compose state it acts on).
const val WT_SYNC_DRIFT_THRESHOLD_MS = 1_500L

data class WtEventEffect(
    val seekToMs: Long? = null,
    val pause: Boolean = false,
    val play: Boolean = false,
)

// Pure decision logic behind PlayerScreen's follower-side event collector —
// split out so the drift-tolerance/seek-vs-no-op rule (the same one web's
// wtEventEffect.ts/computeWtEventEffect implements) is unit-testable without
// a real ExoPlayer instance. Position always snaps immediately for an
// explicit seek/pause/play; a plain `sync` tick (Hermes' periodic
// authoritative tick, or a fresh subscriber's first message) only corrects
// once drift exceeds WT_SYNC_DRIFT_THRESHOLD_MS. paused-state always syncs
// (idempotent — catches a fresh join or a missed event even though explicit
// pause/play already covers most of it).
fun computeWtEventEffect(event: WatchTogetherEvent, currentMs: Long, isPaused: Boolean): WtEventEffect {
    val seekToMs = event.positionMs?.let { ms ->
        val drift = abs(ms - currentMs)
        if (event.type != "sync" || drift > WT_SYNC_DRIFT_THRESHOLD_MS) ms else null
    }
    val pause = event.paused == true && !isPaused
    val play = event.paused == false && isPaused
    return WtEventEffect(seekToMs = seekToMs, pause = pause, play = play)
}
