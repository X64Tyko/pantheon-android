package com.pantheon.android.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.LoginRequest
import kotlinx.coroutines.launch

// Direct-connection only (project plan §7) — a manually-entered Hermes URL,
// no self-hosted/managed relay modes yet (pantheon-relay's /api/* is still a
// stubbed 404). No profile-picker step either yet — that's real scope for a
// later pass (ProfileSelectPage.tsx's equivalent), not skipped by oversight.
class AuthViewModel(
    private val tokenStore: TokenStore,
    private val apiClient: ApiClient,
) : ViewModel() {

    var serverUrlInput by mutableStateOf(tokenStore.serverUrl ?: "")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // True only once both a server URL and a token are already stored —
    // callers use this to decide whether to skip straight to Home. A stale/
    // expired token (flat 30-day TTL server-side, no refresh — see
    // AuthContext.tsx's kairos:unauthorized handling) isn't detected here;
    // that's the first authenticated call's job (see HomeViewModel), same
    // "discover it on the next request" behavior the web app has.
    val hasStoredSession: Boolean
        get() = !tokenStore.serverUrl.isNullOrBlank() && !tokenStore.token.isNullOrBlank()

    fun onServerUrlChange(value: String) { serverUrlInput = value }
    fun onUsernameChange(value: String) { username = value }
    fun onPasswordChange(value: String) { password = value }

    fun confirmServerUrl(): Boolean {
        if (serverUrlInput.isBlank()) { errorMessage = "Enter a server address"; return false }
        tokenStore.serverUrl = serverUrlInput.trim()
        errorMessage = null
        return true
    }

    fun login(onSuccess: () -> Unit) {
        if (username.isBlank() || password.isBlank()) { errorMessage = "Enter username and password"; return }
        isLoading = true
        errorMessage = null
        viewModelScope.launch {
            try {
                val response = apiClient.service.login(LoginRequest(username, password))
                tokenStore.token = response.token
                onSuccess()
            } catch (e: Exception) {
                errorMessage = "Sign in failed: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }

    fun logout() {
        tokenStore.clear()
    }

    companion object {
        fun factory(tokenStore: TokenStore, apiClient: ApiClient) = viewModelFactory {
            initializer { AuthViewModel(tokenStore, apiClient) }
        }
    }
}
