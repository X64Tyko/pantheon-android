package com.pantheon.android.detail

// Mirrors hades/src/components/media/LanguageChips.tsx's LANG_NAMES —
// ISO 639-2 code -> display name, for the same set of languages that map
// covers. Shared by mobile/TV Detail screens.
private val LANG_NAMES: Map<String, String> = mapOf(
    "eng" to "English", "jpn" to "Japanese", "spa" to "Spanish", "fre" to "French", "fra" to "French",
    "ger" to "German", "deu" to "German", "ita" to "Italian", "por" to "Portuguese", "rus" to "Russian",
    "kor" to "Korean", "chi" to "Chinese", "zho" to "Chinese", "ara" to "Arabic", "hin" to "Hindi",
    "dut" to "Dutch", "nld" to "Dutch", "swe" to "Swedish", "nor" to "Norwegian", "dan" to "Danish",
    "fin" to "Finnish", "pol" to "Polish", "tur" to "Turkish", "gre" to "Greek", "ell" to "Greek",
    "heb" to "Hebrew", "tha" to "Thai", "vie" to "Vietnamese", "ces" to "Czech", "cze" to "Czech",
)

fun languageDisplayName(code: String): String = LANG_NAMES[code.lowercase()] ?: code.uppercase()
