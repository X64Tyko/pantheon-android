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
    val theme: TvTheme? = null,
)

// Design tokens generated from hades/src/index.css by hades/scripts/
// generate-tv-tokens.mjs, served by TvManifestService — "styling comes from
// the manifest, not a per-client hardcoded color guess" (see that script's
// own header comment). Null when Kairos couldn't find/parse its token file
// (e.g. a fresh checkout before the generator has ever run) — callers must
// have a hardcoded fallback for that case, same as any other optional
// manifest field.
data class TvTheme(val version: Int, val tokens: TvThemeTokens)

// spacing/radii/transitions/sizing/fontSizes/fonts are all raw CSS value
// strings (e.g. "12px", "0.2s", "'Chakra Petch', sans-serif") — unlike
// colors there's no oklch()-to-hex reduction needed, generate-tv-tokens.mjs
// just passes the declared value through as-is. See PantheonMetrics.kt for
// the parsed/typed (Dp, millis) values screens actually consume, with
// hardcoded fallbacks for tokens this client doesn't find. `shadows`/`other`
// (see the generator's categoryOf()) are still deliberately unmodeled —
// composite multi-layer CSS values with no single-number Compose equivalent
// to parse them into; Gson ignores unmapped JSON keys, so this costs nothing.
data class TvThemeTokens(
    val colors: Map<String, TvThemeColor> = emptyMap(),
    val spacing: Map<String, String> = emptyMap(),
    val radii: Map<String, String> = emptyMap(),
    val transitions: Map<String, String> = emptyMap(),
    val sizing: Map<String, String> = emptyMap(),
    val fontSizes: Map<String, String> = emptyMap(),
    val fonts: Map<String, String> = emptyMap(),
)

// hex is null for composite values the generator can't reduce to a single
// color (gradients, multi-layer shadows, `var()` references) — see
// generate-tv-tokens.mjs's toHex(). Always check for null before using.
data class TvThemeColor(val css: String, val hex: String? = null)

data class TvHomeSection(val rows: List<TvHomeRow>)
data class TvZoneSection(val zones: List<TvZone>)

data class TvHomeRow(
    val id: String,
    val order: Int,
    val type: String, // "hero" | "shelf" | "guide" | "watch-together"
    val title: String? = null,
    // Opaque — forwarded verbatim as query params to GET /api/tv/shelf-items
    // (see KairosApi.getShelfItems). Never inspected/branched on client-side;
    // the server decides what each key means and how to resolve it into
    // tiles, so a new shelf "shape" (e.g. a "mixed" shelf, which the old
    // dataSource.endpoint design couldn't express at all — see
    // HomeViewModel's own comment) never needs a client-side change.
    val filter: Map<String, Any>? = null,
    val itemAction: String? = null,
    val endTile: String? = null,
    val emptyBehavior: String? = null,
    val requiresArt: Boolean? = null,
    val actions: List<String>? = null,
)

// TvDataSource/TvZone.dataSource removed in kairos v105 — confirmed dead on
// every client (nothing anywhere ever read `.dataSource`, on this client or
// hades' /tv), a stale leftover from before Home rows moved to the opaque
// `filter` shape TvHomeRow.filter carries. Was a half-implemented per-zone
// endpoint/queryParam config no client ever actually fetched through
// automatically.

// GET /api/tv/shelf-items' response item shape — render-ready, uniform
// across show/movie/episode content types (see kairos's MixedTileRow).
data class ShelfItemsResponse(val items: List<ShelfTile>)

data class ShelfTile(
    @SerializedName("content_type") val contentType: String, // "show" | "movie" | "episode"
    val id: String,
    val title: String,
    val thumb: String? = null,
    val art: String? = null,
    @SerializedName("library_id") val libraryId: String? = null,
    val year: Int? = null,
    @SerializedName("audience_rating") val audienceRating: Double? = null,
    val watched: Boolean = false,
    @SerializedName("view_count") val viewCount: Long = 0,
    @SerializedName("episode_count") val episodeCount: Int = 0, // shows only
    @SerializedName("duration_ms") val durationMs: Long = 0,    // episodes only
    val season: Int = 0, val episode: Int = 0,                  // episodes only
    @SerializedName("show_id") val showId: String? = null,      // episodes only
    @SerializedName("show_title") val showTitle: String? = null, // episodes only
    // Shows only, populated only when the row's filter.sort is
    // "recently_aired" — needed by the "Recently Aired" shelf's
    // play-latest-episode itemAction.
    @SerializedName("latest_episode") val latestEpisode: ShelfTileLatestEpisode? = null,
)

data class ShelfTileLatestEpisode(
    @SerializedName("episode_id") val episodeId: String,
    val season: Int,
    val episode: Int,
    @SerializedName("air_date") val airDate: String,
)

data class TvZone(
    val id: String,
    val order: Int,
    val filterFields: List<String>? = null,
    val sortOptions: List<String>? = null,
    val itemAction: String? = null,
    val showOnly: Boolean? = null,
    // Which fields this zone renders — same "server owns which fields
    // exist, client owns how each one is rendered" split filterFields/
    // sortOptions already use (kairos v82/v83). Currently only
    // detail/meta-block declares this (kairos v97); null/empty means a
    // manifest that predates it, so callers fall back to their own fixed
    // field set rather than rendering nothing.
    val fields: List<String>? = null,
    // Which action buttons this zone offers — currently only detail's
    // play-button zone declares this (kairos v105: "play"/"play-from-beginning"/
    // "watch-together"). Loose List<String> rather than a closed enum, same
    // "server owns the vocabulary" reasoning as filterFields/fields. Null
    // means a manifest predating this field (or a zone that's never declared
    // it) — callers should default to showing whatever they showed before
    // this field existed, not nothing.
    val actions: List<String>? = null,
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
