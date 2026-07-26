package com.pantheon.android.api.dto

import com.google.gson.annotations.SerializedName

// Mirrors hades/src/api/types.ts's WatchTogetherSession — Kairos-owned
// identity/discovery (who's hosting, what content, is it still open). See
// kairos/src/api/services/WatchTogetherService.cpp's describeSession for the
// exact shape. Episode-only fields (season/episode/show_id/show_title) are
// null for a movie session, same optional-per-content-type convention movie/
// episode's own DTOs (Content.kt) already use.
data class WatchTogetherSession(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("host_user_id") val hostUserId: String,
    @SerializedName("host_username") val hostUsername: String,
    @SerializedName("content_type") val contentType: String, // "movie" | "episode"
    @SerializedName("content_id") val contentId: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    @SerializedName("show_id") val showId: String? = null,
    @SerializedName("show_title") val showTitle: String? = null,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("closed_at") val closedAt: Long? = null,
    @SerializedName("member_count") val memberCount: Int = 0,
)

// POST /watch-together/:id/join (Hermes, not Kairos) — same fields as
// WatchTogetherSession plus the live position/paused Kairos never tracks, so
// a joining client can seed ExoPlayer's own start position instead of
// starting at 0 and waiting on a later sync correction. See
// hades/src/player/watchTogetherApi.ts's joinWatchTogether.
data class WatchTogetherJoinResult(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("host_user_id") val hostUserId: String,
    @SerializedName("host_username") val hostUsername: String,
    @SerializedName("content_type") val contentType: String,
    @SerializedName("content_id") val contentId: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    @SerializedName("show_id") val showId: String? = null,
    @SerializedName("show_title") val showTitle: String? = null,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("closed_at") val closedAt: Long? = null,
    @SerializedName("member_count") val memberCount: Int = 0,
    @SerializedName("position_ms") val positionMs: Long = 0,
    val paused: Boolean = true,
)

data class WatchTogetherOk(val ok: Boolean = false)

data class WatchTogetherCommandBody(
    val type: String, // "seek" | "pause" | "play"
    @SerializedName("position_ms") val positionMs: Long? = null,
)

data class WatchTogetherHeartbeatBody(
    @SerializedName("position_ms") val positionMs: Long,
    val paused: Boolean,
)

// One event off Hermes' SSE stream — `sync` is the periodic authoritative-
// position tick (and also always a fresh subscriber's first message);
// `seek`/`pause`/`play` are the host's own explicit actions, applied
// immediately rather than waiting for the next sync tick. Mirrors
// hades/src/player/watchTogetherApi.ts's WatchTogetherEvent exactly.
data class WatchTogetherEvent(
    val type: String,
    @SerializedName("position_ms") val positionMs: Long? = null,
    val paused: Boolean? = null,
)
