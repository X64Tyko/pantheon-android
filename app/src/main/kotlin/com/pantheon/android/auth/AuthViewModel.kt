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
import com.pantheon.android.api.SwitchProfileRequest
import com.pantheon.android.api.dto.AuthUser
import kotlinx.coroutines.launch

// Direct-connection only (project plan §7) — a manually-entered Hermes URL,
// no self-hosted/managed relay modes yet (pantheon-relay's /api/* is still a
// stubbed 404).
class AuthViewModel(
    private val tokenStore: TokenStore,
    private val apiClient: ApiClient,
) : ViewModel() {

    init {
        // Covers the cold-launch-with-a-stored-session case, which bypasses
        // login()/switchProfile() entirely (see PantheonNavHost's own
        // startDestination check) — without this, a client that was already
        // logged in before this Hephaestus instance's most recent restart
        // would never re-declare, and every VOD start would silently fall
        // back to the conservative allowlist until its next real login.
        if (hasStoredSession) declareCapabilities()
    }

    // Fire-and-forget, best-effort — see DeviceCodecCapabilities' own
    // comment for what this declares and why, and ClientCapabilities.h
    // (hephaestus) for why "once per session" (login/profile-switch/app-
    // launch), not literally once ever: the server-side cache is in-memory
    // and gets wiped on every Hephaestus restart, which happens on every
    // image redeploy. A failed declare here just means this session's VOD
    // starts fall back to the conservative h264/aac allowlist, same as
    // before this feature existed — never worth blocking login/switch on.
    private fun declareCapabilities() {
        viewModelScope.launch {
            runCatching { apiClient.service.declareClientCapabilities(DeviceCodecCapabilities.detect()) }
        }
    }

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
    // callers use this to decide whether to skip straight to the profile
    // picker rather than Connect/Login. A stale/expired token (flat 30-day
    // TTL server-side, no refresh — see AuthContext.tsx's
    // kairos:unauthorized handling) isn't detected here; that's the first
    // authenticated call's job (see HomeViewModel), same "discover it on
    // the next request" behavior the web app has.
    val hasStoredSession: Boolean
        get() = !tokenStore.serverUrl.isNullOrBlank() && !tokenStore.token.isNullOrBlank()

    // ── "Who's watching?" picker — mirrors AuthContext.tsx's
    // profiles/switchProfile/confirmCurrentProfile exactly. ──────────────

    var profiles by mutableStateOf<List<AuthUser>>(emptyList())
        private set
    val currentUserId: String? get() = tokenStore.currentUserId

    var pickerError by mutableStateOf<String?>(null)
        private set
    var pickerBusy by mutableStateOf(false)
        private set

    fun loadProfiles(onLoaded: () -> Unit = {}) {
        viewModelScope.launch {
            profiles = runCatching { apiClient.service.getProfiles() }.getOrDefault(emptyList())
            onLoaded()
        }
    }

    // Picking the tile for whichever profile is already the active session
    // needs no PIN and no network call at all — see AuthContext.tsx's
    // confirmCurrentProfile comment for why this matters (an admin profile
    // with no PIN set yet can otherwise never get past its own tile, since
    // switchProfile always requires one for role=admin).
    fun isCurrentProfile(user: AuthUser) = user.userId == currentUserId

    fun confirmCurrentProfile(onSuccess: () -> Unit) = onSuccess()

    fun switchProfile(user: AuthUser, pin: String?, onSuccess: () -> Unit) {
        pickerBusy = true
        pickerError = null
        viewModelScope.launch {
            try {
                val response = apiClient.service.switchProfile(user.userId, SwitchProfileRequest(pin))
                tokenStore.token = response.token
                tokenStore.currentUserId = response.user.userId
                declareCapabilities()
                onSuccess()
            } catch (e: Exception) {
                pickerError = "Failed to switch profile: ${e.message ?: "unknown error"}"
            } finally {
                pickerBusy = false
            }
        }
    }

    // Admin profiles with no PIN configured fall back to a real password
    // sign-in for that specific account, same as ProfileSelectPage.tsx's
    // passwordFor stage — a separate path from the shared username/password
    // fields above (LoginScreen's own state), so switching one doesn't
    // disturb the other.
    fun loginAsProfile(targetUsername: String, targetPassword: String, onSuccess: () -> Unit) {
        pickerBusy = true
        pickerError = null
        viewModelScope.launch {
            try {
                val response = apiClient.service.login(LoginRequest(targetUsername, targetPassword))
                tokenStore.token = response.token
                tokenStore.currentUserId = response.user.userId
                declareCapabilities()
                onSuccess()
            } catch (e: Exception) {
                pickerError = "Sign in failed: ${e.message ?: "unknown error"}"
            } finally {
                pickerBusy = false
            }
        }
    }

    fun clearPickerError() { pickerError = null }

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
                tokenStore.currentUserId = response.user.userId
                declareCapabilities()
                onSuccess()
            } catch (e: Exception) {
                errorMessage = "Sign in failed: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }

    // The forget call has to fire while the token is still valid (it's how
    // Hephaestus knows which cache entry to remove — see
    // ClientCapabilities.h), so it's launched before tokenStore.clear()
    // runs, not after. clear() itself stays outside the runCatching: a
    // failed/timed-out forget call must never leave the device stuck
    // looking logged-in — worst case the entry just lingers server-side
    // until ClientCapabilityCache's own idle-based pruning drops it.
    fun logout() {
        viewModelScope.launch {
            runCatching { apiClient.service.forgetClientCapabilities() }
            tokenStore.clear()
        }
    }

    companion object {
        fun factory(tokenStore: TokenStore, apiClient: ApiClient) = viewModelFactory {
            initializer { AuthViewModel(tokenStore, apiClient) }
        }
    }
}
