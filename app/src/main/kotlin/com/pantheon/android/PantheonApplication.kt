package com.pantheon.android

import android.app.Application
import android.util.Log
import com.pantheon.android.api.ApiClient
import com.pantheon.android.api.dto.ClientLogBody
import com.pantheon.android.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
        installCrashReporting()
    }

    // Local-only — mirrors hades/src/remoteLog.ts's uncaught-error/rejection
    // forwarding (same POST /api/logs/client endpoint, same "server just
    // logs it" backend, see kairos's ConfigService.cpp), not a third-party
    // crash SDK. Best-effort and briefly blocking: this runs on the crashing
    // thread right before the process dies anyway, so a short synchronous
    // network attempt (capped well below the point Android's ANR/watchdog
    // would care) costs nothing the crash wasn't already going to cost, and
    // chaining to the previous default handler afterward means the OS's own
    // crash dialog/logcat entry still happens exactly as it would without
    // this.
    private fun installCrashReporting() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val message = "Uncaught exception on thread \"${thread.name}\": " + Log.getStackTraceString(throwable)
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(2000) {
                        runCatching {
                            apiClient.service.sendClientLog(ClientLogBody("error", message, tokenStore.currentUserId))
                        }
                    }
                }
            } catch (_: Throwable) {
                // Never let crash reporting itself mask the real crash.
            }
            previousHandler?.uncaughtException(thread, throwable)
                ?: run { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
}
