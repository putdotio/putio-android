package io.putdotio.android

import android.content.Intent
import android.net.Uri
import io.putdotio.android.files.FilesItemId

/** Where a supported product link lands; the shell resolves file ids through Files. */
internal sealed interface MobileDeepLink {
    data object Files : MobileDeepLink

    data class File(val id: FilesItemId) : MobileDeepLink

    data object Transfers : MobileDeepLink

    data object Search : MobileDeepLink

    data object History : MobileDeepLink

    data object Trash : MobileDeepLink

    data object Downloads : MobileDeepLink
}

/**
 * Accepts `https://app.put.io/...` and `putio://...` for the destinations the shell
 * owns. Query strings and fragments are ignored; anything else returns null so the
 * app opens normally. Never log the incoming URI: web links can carry tokens.
 */
internal fun parseMobileDeepLink(uri: Uri?): MobileDeepLink? {
    if (uri == null) return null
    val segments = when {
        uri.scheme == "putio" && uri.host != "auth" -> listOfNotNull(uri.host) + uri.pathSegments
        uri.scheme == "https" && uri.host in WEB_HOSTS -> uri.pathSegments
        else -> return null
    }.filter { it.isNotBlank() }
    return when (segments.firstOrNull()) {
        null, "files" -> when (segments.size) {
            0, 1 -> MobileDeepLink.Files
            2 -> segments[1].toLongOrNull()?.takeIf { it > 0L }?.let { MobileDeepLink.File(FilesItemId(it)) }
            else -> null
        }
        "transfers" -> MobileDeepLink.Transfers.takeIf { segments.size == 1 }
        "search" -> MobileDeepLink.Search.takeIf { segments.size == 1 }
        "history" -> MobileDeepLink.History.takeIf { segments.size == 1 }
        "trash" -> MobileDeepLink.Trash.takeIf { segments.size == 1 }
        "downloads" -> MobileDeepLink.Downloads.takeIf { segments.size == 1 }
        else -> null
    }
}

/** Removes a recognised URI so a replayed launch intent cannot route again or leak into saved state. */
internal fun Intent.consumeMobileDeepLink(): MobileDeepLink? {
    if (action != Intent.ACTION_VIEW) return null
    val link = parseMobileDeepLink(data) ?: return null
    setDataAndType(null, null)
    return link
}

private val WEB_HOSTS = setOf("app.put.io", "put.io", "www.put.io")
