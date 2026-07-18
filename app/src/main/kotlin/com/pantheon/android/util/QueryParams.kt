package com.pantheon.android.util

// Manifest params travel as JSON (Gson decodes numbers as Double even for
// integers like limit:16) — Retrofit's @QueryMap wants plain strings, and an
// integer-valued Double should serialize as "16", not "16.0". Extracted from
// HomeViewModel (its original home) as a standalone pure function so it's
// directly unit-testable without spinning up a ViewModel/ApiClient.
fun toQueryParams(params: Map<String, Any>?): Map<String, String> {
    if (params.isNullOrEmpty()) return emptyMap()
    return params.entries.associate { (key, value) ->
        val str = when (value) {
            is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
        key to str
    }
}

// Mirrors hades/src/components/media/filterSyntax.ts's quoteIfNeeded —
// quoting a plain word is harmless, so always-quote rather than
// reimplementing its whitespace/paren detection. Extracted from
// LibraryViewModel for the same directly-testable reason as above.
fun quoteFilterValue(v: String): String = "\"" + v.replace("\"", "\\\"") + "\""
