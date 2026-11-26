package io.putdotio.android

import java.util.Locale

public fun String.isUnknownLanguageName(): Boolean =
    trim().let { it.isEmpty() || it.lowercase(Locale.ROOT) in UNKNOWN_LANGUAGE_NAMES }

private val UNKNOWN_LANGUAGE_NAMES = setOf("unknown", "undetermined", "und")
