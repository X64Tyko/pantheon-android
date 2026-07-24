package com.pantheon.android.api

import android.util.Log
import com.pantheon.android.api.dto.ClientLogBody
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// Best-effort forwarding of caught-and-otherwise-invisible errors to the
// server — mirrors hades/src/remoteLog.ts's console.error override, which
// catches broadly at one central point rather than requiring every call
// site to opt in. PantheonApplication's own uncaught-exception handler
// already covers actual crashes; this covers everything that gets caught
// and silently swallowed today — this app has no Log.e/Log.w call sites
// anywhere, every failure path is a runCatching{}.getOrNull()/.catch{} with
// nothing surfaced locally OR remotely. Wired into ApiClient's own
// OkHttp interceptor (network failures + 5xx responses) rather than added
// to individual ViewModels one at a time, same reasoning as the JS version.
//
// GlobalScope is deliberate here, not an oversight — same reasoning as
// GuideViewModel.stopCurrentPreview()'s identical comment: this isn't tied
// to any one screen's lifecycle, and the caller (an OkHttp interceptor
// thread) has already moved on by the time a viewModelScope would get
// cancelled anyway.
object RemoteLog {
    @OptIn(DelicateCoroutinesApi::class)
    fun error(apiClient: ApiClient, userId: String?, tag: String, message: String) {
        Log.e(tag, message)
        GlobalScope.launch(Dispatchers.IO) {
            // Best-effort — a failed log-forwarding attempt must never itself
            // throw somewhere that could mask or compound the real error.
            runCatching { apiClient.service.sendClientLog(ClientLogBody("error", "[$tag] $message", userId)) }
        }
    }
}
