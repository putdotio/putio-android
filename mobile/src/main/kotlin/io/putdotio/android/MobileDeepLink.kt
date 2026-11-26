package io.putdotio.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import io.putdotio.android.auth.MOBILE_OAUTH_HOST
import io.putdotio.android.files.FilesItemId

internal sealed interface MobileDeepLink {
    data object Files : MobileDeepLink

    data class File(val id: FilesItemId) : MobileDeepLink

    data object Transfers : MobileDeepLink

    data object AddTransfer : MobileDeepLink

    data object Search : MobileDeepLink

    data object History : MobileDeepLink

    data object Trash : MobileDeepLink

    data class Downloads(
        val fileId: FilesItemId? = null,
        val play: Boolean = false,
        val userId: Long? = null,
    ) : MobileDeepLink
}

internal fun MobileDeepLink.belongsTo(userId: Long): Boolean =
    (this as? MobileDeepLink.Downloads)?.userId?.let { it == userId } ?: true

internal fun parseMobileDeepLink(uri: Uri?): MobileDeepLink? =
    uri?.productSegments()?.let { deepLinkFor(it, uri.userQuery()) }

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

private fun Uri.userQuery(): String? = if (isHierarchical) getQueryParameter(USER_QUERY) else null

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
    2 -> segments[1].toLongOrNull()?.takeIf { it != 0L }?.let { MobileDeepLink.File(FilesItemId(it)) }
    else -> null
}

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

internal fun MobileDeepLink.pendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        0,
        Intent(Intent.ACTION_VIEW, toRouteUri())
            .setClassName(context, INTERNAL_LINK_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

internal fun Intent.consumeMobileDeepLink(): MobileDeepLink? {
    val uri = data?.takeIf { action == Intent.ACTION_VIEW }
    val segments = uri?.productSegments() ?: return null
    val user = uri.userQuery()
    val internal = component?.className == INTERNAL_LINK_ACTIVITY
    setDataAndType(null, null)
    return deepLinkFor(segments, user)?.let { link ->
        if (!internal && link is MobileDeepLink.Downloads) link.copy(play = false) else link
    }
}

internal const val INTERNAL_LINK_ACTIVITY = "io.putdotio.android.InternalLinkActivity"

private val WEB_HOSTS = setOf("app.put.io", "put.io", "www.put.io")

private const val USER_QUERY = "user"

private const val PLAY_SEGMENT = "play"

private val SECTION_LINKS = mapOf(
    "search" to MobileDeepLink.Search,
    "history" to MobileDeepLink.History,
    "trash" to MobileDeepLink.Trash,
)
