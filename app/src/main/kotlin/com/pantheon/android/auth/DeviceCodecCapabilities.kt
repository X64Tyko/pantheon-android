package com.pantheon.android.auth

import android.content.res.Resources
import android.media.MediaCodecList
import android.media.MediaFormat
import com.pantheon.android.api.dto.ClientCapabilitiesRequest

// Real, per-device decode capability via MediaCodecList — not a guessed or
// hardcoded profile the way a platform with no equivalent runtime query
// (Roku) would need. Native Android via MediaCodec genuinely supports far
// more than a browser's <video>/hls.js path (hardware HEVC/AV1 decode on
// most phones from the last several years, for instance), which a fixed
// server-side h264/aac allowlist has no way to take advantage of — see
// hephaestus/src/stream/ClientCapabilities.h for the server side of this.
//
// Maps to ffprobe codec_name values (what VodSession.cpp's isDirectStreamable
// actually compares against), not MediaFormat MIME types — the two use
// different vocabularies for the same codecs.
object DeviceCodecCapabilities {
    private val VIDEO_MIME_TO_CODEC = mapOf(
        MediaFormat.MIMETYPE_VIDEO_AVC to "h264",
        MediaFormat.MIMETYPE_VIDEO_HEVC to "hevc",
        MediaFormat.MIMETYPE_VIDEO_VP9 to "vp9",
        MediaFormat.MIMETYPE_VIDEO_AV1 to "av1",
    )
    private val AUDIO_MIME_TO_CODEC = mapOf(
        MediaFormat.MIMETYPE_AUDIO_AAC to "aac",
        MediaFormat.MIMETYPE_AUDIO_AC3 to "ac3",
        MediaFormat.MIMETYPE_AUDIO_EAC3 to "eac3",
    )

    fun detect(): ClientCapabilitiesRequest {
        val videoCodecs = mutableSetOf<String>()
        val audioCodecs = mutableSetOf<String>()

        // REGULAR_CODECS excludes ones requiring special/vendor
        // configuration to actually use — matches what ExoPlayer would
        // realistically select for this device anyway.
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue // decode capability only — this is playback, not the transcode/encode side
            for (type in info.supportedTypes) {
                VIDEO_MIME_TO_CODEC[type]?.let { videoCodecs.add(it) }
                AUDIO_MIME_TO_CODEC[type]?.let { audioCodecs.add(it) }
            }
        }

        // Resources.getSystem() (not a passed-in Context) — this is a rough
        // "what resolution is this device's own screen" signal for the
        // server's copy-vs-transcode decision (avoid handing a small screen
        // a full-resolution stream copy it has no use for), not a precise
        // per-window measurement; no Context available at every detect()
        // call site (AuthViewModel isn't an AndroidViewModel) and none is
        // needed for that purpose.
        val metrics = Resources.getSystem().displayMetrics
        val maxHeight = maxOf(metrics.widthPixels, metrics.heightPixels)

        return ClientCapabilitiesRequest(videoCodecs.toList(), audioCodecs.toList(), maxHeight)
    }
}
