package com.pantheon.android.player

import com.pantheon.android.api.dto.WatchTogetherEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression coverage for Watch Together's follower-side event application
// (CHANGELOG.md's Unreleased "Android: Watch Together (mobile + TV)" entry),
// mirroring hades/src/player/wtEventEffect.ts's computeWtEventEffect exactly
// — same drift-tolerant correction rule, same threshold.
class WtEventEffectTest {

    @Test
    fun `in-tolerance sync event is a no-op`() {
        // 1000ms drift is within WT_SYNC_DRIFT_THRESHOLD_MS (1500ms).
        val event = WatchTogetherEvent(type = "sync", positionMs = 61_000L)
        val effect = computeWtEventEffect(event, currentMs = 60_000L, isPaused = false)

        assertNull(effect.seekToMs)
        assertFalse(effect.pause)
        assertFalse(effect.play)
    }

    @Test
    fun `out-of-tolerance sync event corrects position`() {
        // 2000ms drift exceeds the 1500ms threshold.
        val event = WatchTogetherEvent(type = "sync", positionMs = 62_000L)
        val effect = computeWtEventEffect(event, currentMs = 60_000L, isPaused = false)

        assertEquals(62_000L, effect.seekToMs)
    }

    @Test
    fun `drift exactly at the threshold does not correct (strictly greater-than)`() {
        val event = WatchTogetherEvent(type = "sync", positionMs = 61_500L)
        val effect = computeWtEventEffect(event, currentMs = 60_000L, isPaused = false)

        assertNull(effect.seekToMs)
    }

    @Test
    fun `an explicit seek event applies immediately even for a tiny drift`() {
        val event = WatchTogetherEvent(type = "seek", positionMs = 60_100L)
        val effect = computeWtEventEffect(event, currentMs = 60_000L, isPaused = false)

        assertEquals(60_100L, effect.seekToMs)
    }

    @Test
    fun `an explicit pause event applies directly`() {
        val event = WatchTogetherEvent(type = "pause", paused = true)
        val effect = computeWtEventEffect(event, currentMs = 60_000L, isPaused = false)

        assertTrue(effect.pause)
        assertFalse(effect.play)
    }

    @Test
    fun `an explicit play event applies directly`() {
        val event = WatchTogetherEvent(type = "play", paused = false)
        val effect = computeWtEventEffect(event, currentMs = 60_000L, isPaused = true)

        assertTrue(effect.play)
        assertFalse(effect.pause)
    }

    @Test
    fun `pause event is idempotent when already paused`() {
        val event = WatchTogetherEvent(type = "sync", paused = true)
        val effect = computeWtEventEffect(event, currentMs = 60_000L, isPaused = true)

        assertFalse(effect.pause)
        assertFalse(effect.play)
    }

    @Test
    fun `play event is idempotent when already playing`() {
        val event = WatchTogetherEvent(type = "sync", paused = false)
        val effect = computeWtEventEffect(event, currentMs = 60_000L, isPaused = false)

        assertFalse(effect.pause)
        assertFalse(effect.play)
    }

    @Test
    fun `a sync tick with no position field at all leaves position untouched`() {
        val event = WatchTogetherEvent(type = "sync", positionMs = null, paused = null)
        val effect = computeWtEventEffect(event, currentMs = 60_000L, isPaused = false)

        assertNull(effect.seekToMs)
        assertFalse(effect.pause)
        assertFalse(effect.play)
    }
}
