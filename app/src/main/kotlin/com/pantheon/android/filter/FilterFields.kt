package com.pantheon.android.filter

// Mirrors hades/src/components/media/filterFields.ts's FIELD_DEFS —
// label/valueType/ops per field are client-side vocabulary (like the closed
// itemAction vocabulary), but *which* fields are actually offered comes from
// the manifest's filter-pills zone (kairos v82 migration), not this list —
// see LibraryViewModel's own comment. Deliberately excludes library/source/
// title: the library-pills/search-bar zones already cover those.

enum class ValueType { TEXT, NUMBER, RESOLUTION, DECADE }

data class FilterOpDef(val id: String, val label: String)
data class FieldDef(val field: String, val label: String, val valueType: ValueType, val ops: List<FilterOpDef>)

private val TEXT_OPS = listOf(
    FilterOpDef("is", "is"),
    FilterOpDef("is_not", "is not"),
    FilterOpDef("contains", "contains"),
    FilterOpDef("does_not_contain", "does not contain"),
)

val RESOLUTIONS = listOf("4K", "1080p", "720p", "SD")
val DECADES = listOf("2020s", "2010s", "2000s", "1990s", "1980s", "1970s", "1960s", "1950s", "1940s", "1930s")

val FIELD_DEFS: Map<String, FieldDef> = listOf(
    FieldDef("genre", "Genre", ValueType.TEXT, TEXT_OPS),
    FieldDef("content_rating", "Content Rating", ValueType.TEXT, TEXT_OPS),
    FieldDef("studio", "Studio", ValueType.TEXT, TEXT_OPS),
    FieldDef("network", "Network", ValueType.TEXT, TEXT_OPS),
    FieldDef("actor", "Actor", ValueType.TEXT, TEXT_OPS),
    FieldDef("director", "Director", ValueType.TEXT, TEXT_OPS),
    FieldDef("country", "Country", ValueType.TEXT, TEXT_OPS),
    FieldDef("collection", "Collection", ValueType.TEXT, TEXT_OPS),
    FieldDef("year", "Year", ValueType.NUMBER, listOf(FilterOpDef("is", "is"), FilterOpDef("lt", "is before"), FilterOpDef("gt", "is after"))),
    FieldDef("resolution", "Resolution", ValueType.RESOLUTION, listOf(FilterOpDef("is", "is"), FilterOpDef("is_not", "is not"))),
    FieldDef("decade", "Decade", ValueType.DECADE, listOf(FilterOpDef("is", "is"))),
    FieldDef(
        "audience_rating", "Audience Rating", ValueType.NUMBER,
        listOf(FilterOpDef("gte", "is at least"), FilterOpDef("lte", "is at most"), FilterOpDef("gt", "is greater than"), FilterOpDef("lt", "is less than")),
    ),
).associateBy { it.field }
