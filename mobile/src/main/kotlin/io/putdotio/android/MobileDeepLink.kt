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

    /** The Downloads screen, with one row opened when a notification names it. */
    data class Downloads(val fileId: FilesItemId? = null) : MobileDeepLink
}

/**
 * Accepts `https://app.put.io/...` and `putio://...` for the destinations the shell
 * owns. Query strings and fragments are ignored; anything else returns null so the
 * app opens normally. Never log the incoming URI: web links can carry tokens.
 */
internal fun parseMobileDeepLink(uri: Uri?): MobileDeepLink? = uri?.productSegments()?.let(::deepLinkFor)

/** Non-blank path segments of a product URI, with the `putio://` host as the first one; null otherwise. */
private fun Uri.productSegments(): List<String>? {
    val host = host?.lowercase()
    return when {
        scheme == "putio" && host != MOBILE_OAUTH_HOST -> listOfNotNull(host) + pathSegments
        scheme == "https" && host in WEB_HOSTS -> pathSegments
        else -> null
    }?.filter { it.isNotBlank() }
}

private fun deepLinkFor(segments: List<String>): MobileDeepLink? = when (val section = segments.firstOrNull()) {
    null, "files" -> filesDeepLink(segments)
    "downloads" -> downloadsDeepLink(segments)
    else -> SECTION_LINKS[section]?.takeIf { segments.size == 1 }
}

private fun downloadsDeepLink(segments: List<String>): MobileDeepLink? = when (segments.size) {
    1 -> MobileDeepLink.Downloads()
    2 -> segments[1].toLongOrNull()?.takeIf { it > 0L }?.let { MobileDeepLink.Downloads(FilesItemId(it)) }
    else -> null
}

private fun filesDeepLink(segments: List<String>): MobileDeepLink? = when (segments.size) {
    0, 1 -> MobileDeepLink.Files
    2 -> segments[1].toLongOrNull()?.takeIf { it > 0L }?.let { MobileDeepLink.File(FilesItemId(it)) }
    else -> null
}

/** The token-free `putio://` form of a link, for saved state. */
internal fun MobileDeepLink.toRouteUri(): Uri = when (this) {
    MobileDeepLink.Files -> "putio://files"
    is MobileDeepLink.File -> "putio://files/${id.value}"
    MobileDeepLink.Transfers -> "putio://transfers"
    MobileDeepLink.Search -> "putio://search"
    MobileDeepLink.History -> "putio://history"
    MobileDeepLink.Trash -> "putio://trash"
    is MobileDeepLink.Downloads -> fileId?.let { "putio://downloads/${it.value}" } ?: "putio://downloads"
}.toUri()

/**
 * Removes any product URI, recognised or not, so a replayed launch intent cannot route
 * again and a web link's query never reaches saved state. The OAuth callback and
 * foreign URIs are left alone.
 */
internal fun Intent.consumeMobileDeepLink(): MobileDeepLink? {
    val segments = data?.takeIf { action == Intent.ACTION_VIEW }?.productSegments() ?: return null
    setDataAndType(null, null)
    return deepLinkFor(segments)
}

private val WEB_HOSTS = setOf("app.put.io", "put.io", "www.put.io")

private val SECTION_LINKS = mapOf(
    "transfers" to MobileDeepLink.Transfers,
    "search" to MobileDeepLink.Search,
    "history" to MobileDeepLink.History,
    "trash" to MobileDeepLink.Trash,
)
