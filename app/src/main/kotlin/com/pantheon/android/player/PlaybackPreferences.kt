package com.pantheon.android.player

import android.content.Context
import android.content.SharedPreferences

// Local, per-device playback preferences — unlike TokenStore's secrets, these
// are plain (unencrypted) prefs, and unlike Kairos-backed settings (see
// SettingsPage.tsx's hades_debug etc.) they're never synced across devices:
// "pause when backgrounded" is inherently a per-device behavior preference,
// the same way YouTube/Spotify treat it.
//
// pauseOnBackground exists as a real, persisted toggle (not a hardcoded
// constant) from the start even though nothing exposes it in a settings UI
// yet — no general Settings screen exists on Android today. It's the hook a
// future audio/"radio" content type needs: that kind of content should keep
// playing backgrounded the way music apps do, and this is the flag such a
// feature (or a settings screen, whichever lands first) will read/flip.
class PlaybackPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pantheon_playback_prefs", Context.MODE_PRIVATE)

    var pauseOnBackground: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_ON_BACKGROUND, true)
        set(value) = prefs.edit().putBoolean(KEY_PAUSE_ON_BACKGROUND, value).apply()

    private companion object {
        const val KEY_PAUSE_ON_BACKGROUND = "pause_on_background"
    }
}
