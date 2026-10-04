package io.putdotio.android.transfers

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.net.Uri

/**
 * Whether a drag carries something the Add transfer sheet takes: links as text, or a `.torrent`
 * file, which file managers often label as plain bytes. Other files have no intake, so the drop
 * target does not light up for them.
 */
internal fun ClipDescription.isMobileTransferDrop(): Boolean =
    mimeTypes().any { ClipDescription.compareMimeTypes(it, "text/*") || it.isTorrentLike() }

/**
 * What a drop asks the intake for, read as untrusted input like a share: the first content URI when
 * the drag says it carries a torrent, otherwise every text or link item as shared text. A drop with
 * neither gives nothing. Item text is read as given; a content URI is never opened here.
 */
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

/**
 * Takes an intent's or a drop's payload into the draft. A torrent is read from [resolver] off the main
 * thread, never from this app's own providers, and [afterRead] runs once that read returns.
 */
internal fun MobileTransferDraft.receive(
    incoming: MobileIncomingTransfer,
    resolver: ContentResolver,
    ownPackage: String,
    afterRead: () -> Unit = {},
) {
    when (incoming) {
        is MobileIncomingTransfer.Ready -> receive(incoming.transfer)
        is MobileIncomingTransfer.Torrent -> receiveLater {
            try {
                resolver.readMobileTorrent(incoming.uri, ownPackage)
            } finally {
                afterRead()
            }
        }
    }
}

private fun ClipDescription.mimeTypes(): List<String> = (0 until mimeTypeCount).map(::getMimeType)

private fun String.isTorrentLike(): Boolean = this == TORRENT_MEDIA_TYPE || this == OCTET_STREAM

private fun Uri.isLink(): Boolean = scheme?.lowercase() in LINK_SCHEMES

private const val OCTET_STREAM = "application/octet-stream"
private val LINK_SCHEMES = setOf("http", "https", "magnet")
