package io.putdotio.android

import java.util.Locale

/** Names put.io and HLS manifests use for a subtitle whose language nobody recorded. */
public fun String.isUnknownLanguageName(): Boolean =
    trim().let { it.isEmpty() || it.lowercase(Locale.ROOT) in UNKNOWN_LANGUAGE_NAMES }

private val UNKNOWN_LANGUAGE_NAMES = setOf("unknown", "undetermined", "und")
