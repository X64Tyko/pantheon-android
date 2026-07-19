package com.pantheon.android.api.dto

import com.google.gson.annotations.SerializedName

// Mirrors hades/src/api/types.ts's TvManifest exactly — GET /api/tv/manifest,
// see kairos/src/api/services/TvManifestService.cpp for the server side.
// Zones/rows never carry behavior, only placement + a data pointer — see
// that file's own header comment for why. Params/config are left as loosely
// typed maps for the same reason the TS side uses Record<string, unknown>:
// this is composition data, not a fully-typed contract for every future field.

data class TvManifest(
    val version: Int,
    val home: TvHomeSection,
    val library: TvZoneSection,
    val detail: TvZoneSection,
    val guide: TvZoneSection,
)

data class TvHomeSection(val rows: List<TvHomeRow>)
data class TvZoneSection(val zones: List<TvZone>)

data class TvHomeRow(
    val id: String,
    val order: Int,
    val type: String, // "hero" | "shelf" | "guide"
    val title: String? = null,
    val dataSource: TvDataSource? = null,
    val dataSources: TvHeroDataSources? = null,
    val itemAction: String? = null,
    val endTile: String? = null,
    val emptyBehavior: String? = null,
    val requiresArt: Boolean? = null,
    val actions: List<String>? = null,
)

data class TvHeroDataSources(val shows: TvDataSource, val movies: TvDataSource)

data class TvDataSource(
    val endpoint: String? = null,
    val endpoints: List<String>? = null,
    val params: Map<String, Any>? = null,
    val queryParam: String? = null,
)

data class TvZone(
    val id: String,
    val order: Int,
    val dataSource: TvDataSource? = null,
    val filterFields: List<String>? = null,
    val itemAction: String? = null,
    val showOnly: Boolean? = null,
)

// The closed itemAction/endTile vocabulary — matches hades/src/api/types.ts's
// TvItemAction exactly. Kept as plain string constants rather than a Kotlin
// enum since the wire values already ARE the vocabulary; an enum would just
// add a name-mapping seam for no benefit.
object TvItemAction {
    const val OPEN_DETAIL = "open-detail"
    const val PLAY_DIRECT_WITH_POSITION = "play-direct-with-position"
    const val PLAY_LATEST_EPISODE = "play-latest-episode"
    const val PLAY_RESOLVED = "play-resolved"
    const val NAVIGATE_LIBRARY = "navigate-library"
    const val WATCH_LIVE = "watch-live"
}

data class AuthResponse(
    val token: String,
    val user: AuthUser,
)

// Mirrors hades/src/api/types.ts's User, trimmed to what the profile picker
// (GET /api/auth/profiles) actually renders — restricted/has_pin drive the
// picker's badges and PIN-vs-password gating, see ProfileSelectScreen's own
// comment for the exact rules.
data class AuthUser(
    @SerializedName("user_id") val userId: String,
    val username: String,
    val role: String,
    val restricted: Boolean = false,
    @SerializedName("has_pin") val hasPin: Boolean = false,
)
