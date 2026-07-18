package com.pantheon.android.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// Keystore-backed storage for the bearer token and the user-entered server
// URL (direct-connection mode only — see project plan §7, self-hosted/
// managed relay modes aren't buildable yet). Never plain SharedPreferences —
// this is the same class of secret hades/src/api/client.ts keeps in
// localStorage, which has no Android equivalent of "only this app can read it"
// without going through Keystore.
class TokenStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pantheon_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    // The Hermes base URL the user entered during Connect — e.g.
    // "http://192.168.1.50:8000". No scheme/path normalization beyond what
    // ApiClient does at request-build time.
    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
        // serverUrl deliberately survives a clear() — same behavior as
        // AuthContext.tsx's ClearSession(false)/logout distinction: losing
        // the token shouldn't force re-typing the server address too.
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_SERVER_URL = "server_url"
    }
}
