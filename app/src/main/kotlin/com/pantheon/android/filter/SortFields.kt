package com.pantheon.android.filter

// Mirrors hades/src/components/media/LibraryFilters.tsx's SORT_OPTIONS —
// label/dirless per sort mode are client-side vocabulary (like FIELD_DEFS's
// label/valueType/ops above), but *which* modes are actually offered comes
// from the manifest's filter-pills zone's sortOptions (kairos v83
// migration), not this list — same "server owns which, client owns how"
// split filterFields already established.

data class SortDef(val id: String, val label: String, val dirless: Boolean = false)

val SORT_DEFS: Map<String, SortDef> = listOf(
    SortDef("title", "Title"),
    SortDef("recently_added", "Date Added"),
    SortDef("year", "Year"),
    SortDef("audience_rating", "Audience Rating"),
    SortDef("duration", "Duration"),
    // Combined option — shows and movies don't share a single backend sort
    // mode for this (recently_aired vs recently_released), see
    // LibraryViewModel's showSort()/movieSort().
    SortDef("recently_released_or_aired", "Recently Aired/Released"),
    SortDef("random", "Random", dirless = true),
).associateBy { it.id }
