package com.pantheon.android.api

import com.pantheon.android.api.dto.AuthResponse
import com.pantheon.android.api.dto.AuthUser
import com.pantheon.android.api.dto.Channel
import com.pantheon.android.api.dto.ChannelAccessResponse
import com.pantheon.android.api.dto.ClientCapabilitiesRequest
import com.pantheon.android.api.dto.Episode
import com.pantheon.android.api.dto.EpgProgram
import com.pantheon.android.api.dto.FilterValuesResponse
import com.pantheon.android.api.dto.LibraryWithSource
import com.pantheon.android.api.dto.MediaLanguages
import com.pantheon.android.api.dto.Movie
import com.pantheon.android.api.dto.MovieDetail
import com.pantheon.android.api.dto.NextEpisode
import com.pantheon.android.api.dto.PagedResult
import com.pantheon.android.api.dto.PreviewStartRequest
import com.pantheon.android.api.dto.PreviewStartResponse
import com.pantheon.android.api.dto.PreviewSwitchRequest
import com.pantheon.android.api.dto.ResolvedPlayTarget
import com.pantheon.android.api.dto.Show
import com.pantheon.android.api.dto.ShowDetail
import com.pantheon.android.api.dto.TvManifest
import com.pantheon.android.api.dto.VodStartRequest
import com.pantheon.android.api.dto.VodStartResponse
import com.pantheon.android.api.dto.WatchProgress
import com.pantheon.android.api.dto.WatchProgressBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap

// Mirrors hades/src/api/client.ts's contract 1:1 — same endpoints, same
// param names. Every request carries Authorization: Bearer <token> (once
// logged in) and X-Pantheon-Surface: tv unconditionally — see
// ApiClient.kt's interceptor, matching /tv's own behavior and
// pantheon-roku's ApiClient.brs convention (this app is always a viewer
// surface, never an admin one).

data class LoginRequest(val username: String, val password: String)
data class SwitchProfileRequest(val pin: String? = null)

interface KairosApi {
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    // "Who's watching?" picker — mirrors hades/src/api/client.ts's
    // getProfiles/switchProfile exactly, including switchProfile returning a
    // brand new token (the active session really does become that profile,
    // not just a display-name change).
    @GET("api/auth/profiles")
    suspend fun getProfiles(): List<AuthUser>

    @POST("api/auth/switch/{id}")
    suspend fun switchProfile(@Path("id") userId: String, @Body body: SwitchProfileRequest): AuthResponse

    @GET("api/tv/manifest")
    suspend fun getTvManifest(): TvManifest

    @GET("api/shows")
    suspend fun getShows(@QueryMap params: Map<String, String>): PagedResult<Show>

    @GET("api/movies")
    suspend fun getMovies(@QueryMap params: Map<String, String>): PagedResult<Movie>

    @GET("api/watch-progress")
    suspend fun getWatchProgress(): List<WatchProgress>

    @GET("api/shows/{id}/resolve-play-target")
    suspend fun getResolvedPlayTarget(@Path("id") showId: String): ResolvedPlayTarget?

    @GET("api/libraries")
    suspend fun getLibraries(): List<LibraryWithSource>

    // `field` values are plain metadata field names, e.g. "genre" — see
    // hades/src/api/client.ts's getFilterValues.
    @GET("api/metadata/values")
    suspend fun getFilterValues(@Query("field") field: String): FilterValuesResponse

    @GET("api/shows/{id}")
    suspend fun getShow(@Path("id") id: String): ShowDetail

    @GET("api/movies/{id}")
    suspend fun getMovie(@Path("id") id: String): MovieDetail

    @GET("api/shows/{id}/episodes")
    suspend fun getEpisodes(@Path("id") id: String): List<Episode>

    @GET("api/episodes/{id}/next-episode")
    suspend fun getNextEpisode(@Path("id") episodeId: String): NextEpisode?

    @GET("api/shows/{id}/languages")
    suspend fun getShowLanguages(@Path("id") id: String): MediaLanguages

    @GET("api/movies/{id}/languages")
    suspend fun getMovieLanguages(@Path("id") id: String): MediaLanguages

    // ── Guide ────────────────────────────────────────────────────────────────
    @GET("api/channels")
    suspend fun getChannels(): List<Channel>

    @GET("api/channels/{id}/epg")
    suspend fun getChannelEpg(@Path("id") channelId: String, @Query("hours") hours: Int, @Query("from") from: Long): List<EpgProgram>

    @GET("api/channels/{id}/access-check")
    suspend fun checkChannelAccess(@Path("id") channelId: String): ChannelAccessResponse

    // ── Live preview + playback ── these hit Hermes's /stream/* routes
    // (Hephaestus), not /api/*, but share this same Retrofit interface/base
    // URL — see hades/src/guide/previewApi.ts and player/playbackApi.ts.
    @POST("stream/preview/start")
    suspend fun startPreview(@Body body: PreviewStartRequest): PreviewStartResponse

    @POST("stream/preview/{id}/switch")
    suspend fun switchPreview(@Path("id") sessionId: String, @Body body: PreviewSwitchRequest): Response<Unit>

    @POST("stream/preview/{id}/stop")
    suspend fun stopPreview(@Path("id") sessionId: String): Response<Unit>

    @POST("stream/vod/start")
    suspend fun startVodPlayback(@Body body: VodStartRequest): VodStartResponse

    @POST("stream/vod/{id}/stop")
    suspend fun stopVodPlayback(@Path("id") sessionId: String): Response<Unit>

    // Declares this device's real decode capability once per session
    // (login/profile-switch/app-launch — see AuthViewModel's own comment on
    // why "once per session," not literally once ever), so VodSession's
    // direct-play decision can check a source file's actual codecs against
    // what this specific client can really play instead of a fixed
    // h264/aac allowlist. forgetClientCapabilities is the logout-time
    // counterpart — see hephaestus/src/stream/ClientCapabilities.h.
    @POST("stream/client-capabilities")
    suspend fun declareClientCapabilities(@Body body: ClientCapabilitiesRequest): Response<Unit>

    @DELETE("stream/client-capabilities")
    suspend fun forgetClientCapabilities(): Response<Unit>

    @PUT("api/watch-progress/{contentType}/{id}")
    suspend fun putWatchProgress(@Path("contentType") contentType: String, @Path("id") id: String, @Body body: WatchProgressBody): Response<Unit>
}
