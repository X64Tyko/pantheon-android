package com.pantheon.android.api

import com.pantheon.android.auth.TokenStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

// Rebuildable per-server-URL Retrofit client — the URL isn't known at build
// time (direct-connection mode, see project plan §7: a user-entered Hermes
// URL, not a baked-in constant). Every request carries Authorization: Bearer
// <token> (once logged in) and X-Pantheon-Surface: tv unconditionally, same
// convention as /tv itself and pantheon-roku's ApiClient.brs — this app is
// always a viewer surface.
class ApiClient(private val tokenStore: TokenStore) {

    private var cachedServerUrl: String? = null
    private var cachedService: KairosApi? = null

    val service: KairosApi
        get() {
            val url = normalizedBaseUrl()
            if (cachedService == null || cachedServerUrl != url) {
                cachedServerUrl = url
                cachedService = buildRetrofit(url).create(KairosApi::class.java)
            }
            return cachedService!!
        }

    // media/thumb/art URLs are plain paths from Show/Movie responses
    // (e.g. "/api/shows/:id/thumb") — this appends the server origin and
    // auth token, mirroring hades/src/api/client.ts's mediaUrl() exactly.
    fun mediaUrl(path: String): String {
        val base = normalizedBaseUrl().trimEnd('/')
        val token = tokenStore.token
        val sep = if (path.contains('?')) '&' else '?'
        return if (token != null) "$base$path$sep" + "token=$token" else "$base$path"
    }

    // Stream manifest URLs (live HLS playlist path, or VOD's manifest_url
    // response field) are relative paths with no token appended — mirrors
    // hades/src/player/playbackApi.ts's liveChannelManifestUrl()/
    // startVodPlayback() exactly, neither of which append auth to the
    // stream URL itself (unlike mediaUrl() above, which does for images).
    fun streamUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = normalizedBaseUrl().trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    fun liveChannelManifestUrl(channelId: String): String = streamUrl("/stream/hls/channels/$channelId/playlist.m3u8")

    private fun normalizedBaseUrl(): String {
        val raw = tokenStore.serverUrl?.trim().orEmpty()
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val authInterceptor = Interceptor { chain: Interceptor.Chain ->
            val builder = chain.request().newBuilder()
                .addHeader("X-Pantheon-Surface", "tv")
            tokenStore.token?.let { builder.addHeader("Authorization", "Bearer $it") }
            chain.proceed(builder.build())
        }
        // Broad, central error-forwarding (see RemoteLog's own comment) —
        // network failures and 5xx server errors, on every request this app
        // makes, without touching each individual call site. 4xx is
        // deliberately excluded: those are routinely expected/handled
        // outcomes (an optional lookup 404ing, a token refresh 401) rather
        // than genuine failures, and logging every one would bury real
        // problems in noise. Skips the log endpoint's own path outright —
        // a failed sendClientLog call re-entering this same interceptor
        // would otherwise recurse into logging its own failure forever.
        val errorReportingInterceptor = Interceptor { chain: Interceptor.Chain ->
            val request = chain.request()
            if (request.url.encodedPath.contains("/logs/client")) {
                chain.proceed(request)
            } else {
                try {
                    val response = chain.proceed(request)
                    if (response.code >= 500) {
                        RemoteLog.error(this, tokenStore.currentUserId, "api",
                            "${request.method} ${request.url.encodedPath} -> ${response.code}")
                    }
                    response
                } catch (e: IOException) {
                    RemoteLog.error(this, tokenStore.currentUserId, "api",
                        "${request.method} ${request.url.encodedPath} failed: ${e.message}")
                    throw e
                }
            }
        }
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(errorReportingInterceptor)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
