package io.putdotio.android.transfers

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.net.Uri

internal fun ClipDescription.isMobileTransferDrop(): Boolean =
    mimeTypes().any { ClipDescription.compareMimeTypes(it, "text/*") || it.isTorrentLike() }

internal fun ClipData.toMobileIncomingTransfer(): MobileIncomingTransfer? {
    val items = (0 until itemCount).map(::getItemAt)
    val torrent = items
        .takeIf { description?.mimeTypes().orEmpty().any { it.isTorrentLike() } }
        ?.firstNotNullOfOrNull { it.uri?.takeIf { uri -> uri.scheme == ContentResolver.SCHEME_CONTENT } }
    val text = items
        .mapNotNull { item -> item.text?.toString() ?: item.uri?.takeIf(Uri::isLink)?.toString() }
        .filter { it.isNotBlank() }
    return when {
        torrent != null -> MobileIncomingTransfer.Torrent(torrent)
        text.isEmpty() -> null
        else -> MobileIncomingTransfer.Ready(parseMobileSharedTransfer(text.joinToString("\n")))
    }
}

internal fun MobileTransferDraft.receive(
    incoming: MobileIncomingTransfer,
    resolver: ContentResolver,
    ownPackage: String,
    afterRead: () -> Unit = {},
) {
    when (incoming) {
        is MobileIncomingTransfer.Ready -> receive(incoming.transfer)
        is MobileIncomingTransfer.Torrent ->
            receiveLater(read = { resolver.readMobileTorrent(incoming.uri, ownPackage) }, onDone = afterRead)
    }
}

private fun ClipDescription.mimeTypes(): List<String> = (0 until mimeTypeCount).map(::getMimeType)

private fun String.isTorrentLike(): Boolean = this == TORRENT_MEDIA_TYPE || this == OCTET_STREAM

private fun Uri.isLink(): Boolean = scheme?.lowercase() in LINK_SCHEMES

private const val OCTET_STREAM = "application/octet-stream"
private val LINK_SCHEMES = setOf("http", "https", "magnet")
