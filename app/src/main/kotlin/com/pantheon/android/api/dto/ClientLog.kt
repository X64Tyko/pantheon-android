package com.pantheon.android.api.dto

import com.google.gson.annotations.SerializedName

// Mirrors hades/src/api/client.ts's sendClientLog — same POST /api/logs/client
// endpoint, purely local (see kairos's ConfigService.cpp handler: it just
// prints the line, no third-party anything). user_id is best-effort
// attribution (TokenStore.currentUserId), same role as Hades'
// currentUserRef.
data class ClientLogBody(
    val level: String,
    val message: String,
    @SerializedName("user_id") val userId: String?,
)
