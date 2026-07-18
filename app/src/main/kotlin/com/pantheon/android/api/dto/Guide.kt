package com.pantheon.android.api.dto

import com.google.gson.annotations.SerializedName

// Mirrors hades/src/api/types.ts's Channel/EpgProgram — only the fields the
// Guide screen actually renders.

data class Channel(
    @SerializedName("channel_id") val channelId: String,
    val name: String,
    val number: Int,
    @SerializedName("logo_path") val logoPath: String? = null,
)

data class EpgProgram(
    @SerializedName("item_type") val itemType: String, // "episode" | "movie" | "filler"
    val title: String,
    @SerializedName("show_title") val showTitle: String? = null,
    val season: Int? = null,
    @SerializedName("episode_num") val episodeNum: Int? = null,
    @SerializedName("wall_clock_start_ms") val wallClockStartMs: Long,
    @SerializedName("wall_clock_end_ms") val wallClockEndMs: Long,
    val overview: String? = null,
)

data class ChannelAccessResponse(val allowed: Boolean)

// POST /stream/preview/start|:/switch — see hades/src/guide/previewApi.ts.
// These hit Hermes's /stream/* routes directly, same as playback endpoints
// below, not the /api/* namespace — see KairosApi's own note on why they
// still live in the same Retrofit interface.
data class PreviewStartRequest(
    @SerializedName("channel_id") val channelId: String,
    @SerializedName("hdr_capable") val hdrCapable: Boolean = false,
)
data class PreviewSwitchRequest(@SerializedName("channel_id") val channelId: String)
data class PreviewStartResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("manifest_url") val manifestUrl: String,
)
