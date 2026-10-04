package io.putdotio.android

import android.app.PendingIntent
import android.content.Context
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

    /** Transfers with the Add transfer sheet open; nothing is added until the viewer taps Add. */
    data object AddTransfer : MobileDeepLink

    data object Search : MobileDeepLink

    data object History : MobileDeepLink

    data object Trash : MobileDeepLink

    /**
     * The Downloads screen, with one row opened when a notification names it; [play] plays that
     * row's copy on this device instead. A link naming [userId] does nothing under any other account.
     */
    data class Downloads(
        val fileId: FilesItemId? = null,
        val play: Boolean = false,
        val userId: Long? = null,
    ) : MobileDeepLink
}

/** Whether the signed-in [userId] may follow this link; one scoped to another account is dropped. */
internal fun MobileDeepLink.belongsTo(userId: Long): Boolean =
    (this as? MobileDeepLink.Downloads)?.userId?.let { it == userId } ?: true

/**
 * Accepts `https://app.put.io/...` and `putio://...` for the destinations the shell
 * owns. Fragments and queries are ignored, except a Downloads link's `user`; anything
 * else returns null so the app opens normally. Never log the incoming URI: web links
 * can carry tokens.
 */
internal fun parseMobileDeepLink(uri: Uri?): MobileDeepLink? =
    uri?.productSegments()?.let { deepLinkFor(it, uri.userQuery()) }

/** Non-blank path segments of a product URI, with the `putio://` host as the first one; null otherwise. */
private fun Uri.productSegments(): List<String>? {
    val host = host?.lowercase()
    return when {
        scheme == "putio" && host != MOBILE_OAUTH_HOST -> listOfNotNull(host) + pathSegments
        scheme == "https" && host in WEB_HOSTS -> pathSegments
        else -> null
    }?.filter { it.isNotBlank() }
}

private fun deepLinkFor(segments: List<String>, user: String?): MobileDeepLink? =
    when (val section = segments.firstOrNull()) {
        null, "files" -> filesDeepLink(segments)
        "downloads" -> downloadsDeepLink(segments, user)
        "transfers" -> when (segments.drop(1)) {
            emptyList<String>() -> MobileDeepLink.Transfers
            listOf("add") -> MobileDeepLink.AddTransfer
            else -> null
        }
        else -> SECTION_LINKS[section]?.takeIf { segments.size == 1 }
    }

/** An opaque URI such as `putio:x` has no query at all. */
private fun Uri.userQuery(): String? = if (isHierarchical) getQueryParameter(USER_QUERY) else null

/** `downloads`, `downloads/<id>` or `downloads/<id>/play`; a `user` that is not an account id voids the link. */
private fun downloadsDeepLink(segments: List<String>, user: String?): MobileDeepLink? {
    val userId = user?.toLongOrNull()?.takeIf { it > 0L }
    val fileId = segments.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }?.let(::FilesItemId)
    val play = when (segments.drop(2)) {
        emptyList<String>() -> false
        listOf(PLAY_SEGMENT) -> true
        else -> null
    }
    val valid = play != null && (user == null || userId != null) && (segments.size == 1 || fileId != null)
    return if (valid) MobileDeepLink.Downloads(fileId, play = play == true, userId = userId) else null
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
    MobileDeepLink.AddTransfer -> "putio://transfers/add"
    MobileDeepLink.Search -> "putio://search"
    MobileDeepLink.History -> "putio://history"
    MobileDeepLink.Trash -> "putio://trash"
    is MobileDeepLink.Downloads -> buildString {
        append("putio://downloads")
        fileId?.let { append("/${it.value}") }
        if (play && fileId != null) append("/$PLAY_SEGMENT")
        userId?.let { append("?$USER_QUERY=$it") }
    }
}.toUri()

/**
 * An explicit, immutable pending intent that opens this link in MainActivity, for notifications and
 * widgets. The link is its data, so each account, row and action keeps its own pending intent.
 */
internal fun MobileDeepLink.pendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        0,
        Intent(Intent.ACTION_VIEW, toRouteUri())
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/**
 * Removes any product URI, recognised or not, so a replayed launch intent cannot route
 * again and a web link's query never reaches saved state. The OAuth callback and
 * foreign URIs are left alone.
 */
internal fun Intent.consumeMobileDeepLink(): MobileDeepLink? {
    val uri = data?.takeIf { action == Intent.ACTION_VIEW }
    val segments = uri?.productSegments() ?: return null
    val user = uri.userQuery()
    setDataAndType(null, null)
    return deepLinkFor(segments, user)
}

private val WEB_HOSTS = setOf("app.put.io", "put.io", "www.put.io")

/** Scopes a Downloads link to one account; the only query a product link reads. */
private const val USER_QUERY = "user"

private const val PLAY_SEGMENT = "play"

private val SECTION_LINKS = mapOf(
    "search" to MobileDeepLink.Search,
    "history" to MobileDeepLink.History,
    "trash" to MobileDeepLink.Trash,
)
