package com.pantheon.android.api.dto

import com.google.gson.annotations.SerializedName

// GET /api/shows/:id/resolve-play-target — see kairos/src/api/services/
// PlaybackService.cpp and hades/src/player/resolvePlayTarget.ts. No client
// (this one included) implements the resume-episode branch itself anymore.
data class ResolvedPlayTarget(
    val kind: String, // "movie" | "episode"
    val id: String,
    @SerializedName("position_ms") val positionMs: Long,
)
