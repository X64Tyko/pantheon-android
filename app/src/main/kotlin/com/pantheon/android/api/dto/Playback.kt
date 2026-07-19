package com.pantheon.android.api.dto

import com.google.gson.annotations.SerializedName

// Mirrors hades/src/player/playbackApi.ts's VOD contract — these hit
// Hermes's /stream/* routes (Hephaestus's stream engine), not /api/*.

data class VodStartRequest(
    @SerializedName("content_type") val contentType: String, // "movie" | "episode"
    @SerializedName("content_id") val contentId: String,
    @SerializedName("audio_track") val audioTrack: Int? = null,
    @SerializedName("subtitle_track") val subtitleTrack: Int? = null,
    @SerializedName("position_ms") val positionMs: Long? = null,
    @SerializedName("hdr_capable") val hdrCapable: Boolean = false,
)

data class VodTrackAudio(val index: Int, val language: String? = null, val title: String? = null)
data class VodTrackSubtitle(val index: Int, val language: String? = null, val title: String? = null)
data class VodTracks(val audio: List<VodTrackAudio>? = null, val subtitles: List<VodTrackSubtitle>? = null)

data class VodStartResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("manifest_url") val manifestUrl: String,
    @SerializedName("subtitle_url") val subtitleUrl: String? = null,
    @SerializedName("direct_play") val directPlay: Boolean = false,
    @SerializedName("duration_ms") val durationMs: Long = 0,
    val title: String = "",
    val tracks: VodTracks? = null,
    @SerializedName("subtitle_burned_in") val subtitleBurnedIn: Boolean = false,
)

data class WatchProgressBody(
    @SerializedName("position_ms") val positionMs: Long,
    @SerializedName("duration_ms") val durationMs: Long,
    val completed: Boolean? = null,
)

// POST /stream/client-capabilities -- this device's real decode capability
// (see com.pantheon.android.auth.DeviceCodecCapabilities), keyed server-side
// by bearer token so VodSession's direct-play decision (hephaestus/src/
// stream/VodSession.cpp's isDirectPlayable) can check a source file's
// actual codecs against what *this* client can really play instead of a
// fixed h264/aac allowlist. ffprobe codec_name values, not MIME types —
// see DeviceCodecCapabilities' own mapping.
data class ClientCapabilitiesRequest(
    @SerializedName("video_codecs") val videoCodecs: List<String>,
    @SerializedName("audio_codecs") val audioCodecs: List<String>,
)
