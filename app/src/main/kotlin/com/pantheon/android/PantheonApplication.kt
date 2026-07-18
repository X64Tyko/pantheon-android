package com.pantheon.android

import android.app.Application
import com.pantheon.android.api.ApiClient
import com.pantheon.android.auth.TokenStore

// No DI framework yet (Hilt would be the idiomatic next step once this
// project has enough screens to justify it) — a small manual singleton
// holder is enough for the two things every screen needs: the token store
// and the API client built from it.
class PantheonApplication : Application() {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var apiClient: ApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        apiClient = ApiClient(tokenStore)
    }
}
