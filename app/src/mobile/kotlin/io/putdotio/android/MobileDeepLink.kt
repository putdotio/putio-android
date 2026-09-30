package io.putdotio.android

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import io.putdotio.android.auth.MOBILE_OAUTH_HOST
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
    val host = uri.host?.lowercase()
    val segments = when {
        uri.scheme == "putio" && host != MOBILE_OAUTH_HOST -> listOfNotNull(host) + uri.pathSegments
        uri.scheme == "https" && host in WEB_HOSTS -> uri.pathSegments
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

/** The token-free `putio://` form of a link, for saved state. */
internal fun MobileDeepLink.toRouteUri(): Uri = when (this) {
    MobileDeepLink.Files -> "putio://files"
    is MobileDeepLink.File -> "putio://files/${id.value}"
    MobileDeepLink.Transfers -> "putio://transfers"
    MobileDeepLink.Search -> "putio://search"
    MobileDeepLink.History -> "putio://history"
    MobileDeepLink.Trash -> "putio://trash"
    MobileDeepLink.Downloads -> "putio://downloads"
}.toUri()

/**
 * Removes any product URI, recognised or not, so a replayed launch intent cannot route
 * again and a web link's query never reaches saved state. The OAuth callback and
 * foreign URIs are left alone.
 */
internal fun Intent.consumeMobileDeepLink(): MobileDeepLink? {
    if (action != Intent.ACTION_VIEW) return null
    val uri = data ?: return null
    val host = uri.host?.lowercase()
    val product = (uri.scheme == "putio" && host != MOBILE_OAUTH_HOST) || (uri.scheme == "https" && host in WEB_HOSTS)
    if (!product) return null
    setDataAndType(null, null)
    return parseMobileDeepLink(uri)
}


private val WEB_HOSTS = setOf("app.put.io", "put.io", "www.put.io")
