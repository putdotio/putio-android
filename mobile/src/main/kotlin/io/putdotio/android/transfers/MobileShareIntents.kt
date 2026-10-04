package io.putdotio.android.transfers

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal enum class MobileShareValidation {
    InvalidLink,
    TooManyLinks,
    TooLong,
    NotAdded,
    InvalidTorrent,
    TorrentTooLarge,
}

internal class MobileSharedTransfer(
    val input: String = "",
    val validation: MobileShareValidation? = null,
    val torrent: TorrentUpload? = null,
) {
    override fun toString(): String =
        "MobileSharedTransfer(<redacted>, validation=$validation, torrent=${torrent != null})"
}

/** What an incoming intent asks for; a torrent still has to be read from its content URI. */
internal sealed interface MobileIncomingTransfer {
    class Ready(val transfer: MobileSharedTransfer) : MobileIncomingTransfer

    class Torrent(val uri: Uri) : MobileIncomingTransfer {
        override fun toString(): String = "Torrent(<redacted>)"
    }
}

/**
 * Accepts shared text, `magnet:` links and `.torrent` content as untrusted input for the add-transfer
 * draft, which the user still confirms. Removes the payload even when malformed; only the in-memory
 * draft may retain it.
 */
internal fun Intent.consumeMobileIncomingTransfer(): MobileIncomingTransfer? {
    val viewedUri = data.takeIf { action == Intent.ACTION_VIEW }
    val kind = incomingKind(viewedUri) ?: return null
    return try {
        when (kind) {
            IncomingKind.Text -> MobileIncomingTransfer.Ready(sharedText())
            IncomingKind.Magnet -> MobileIncomingTransfer.Ready(parseMobileMagnetLink(viewedUri.toString()))
            IncomingKind.Torrent -> sharedTorrent(viewedUri)
        }
    } catch (_: android.os.BadParcelableException) {
        MobileIncomingTransfer.Ready(MobileSharedTransfer(validation = kind.malformed))
    } catch (_: ClassCastException) {
        MobileIncomingTransfer.Ready(MobileSharedTransfer(validation = kind.malformed))
    } finally {
        replaceExtras(android.os.Bundle())
        clipData = null
        setDataAndType(null, type)
        selector = null
    }
}

private fun Intent.incomingKind(viewedUri: Uri?): IncomingKind? =
    when {
        action == Intent.ACTION_SEND && type == "text/plain" -> IncomingKind.Text
        viewedUri?.scheme.equals(MAGNET_SCHEME, ignoreCase = true) -> IncomingKind.Magnet
        // The manifest routes VIEW content URIs here only for the BitTorrent type.
        viewedUri?.scheme == ContentResolver.SCHEME_CONTENT -> IncomingKind.Torrent
        action == Intent.ACTION_SEND && type == TORRENT_MEDIA_TYPE -> IncomingKind.Torrent
        else -> null
    }

private fun Intent.sharedTorrent(viewedUri: Uri?): MobileIncomingTransfer =
    (viewedUri ?: IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
        ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        ?.let(MobileIncomingTransfer::Torrent)
        ?: MobileIncomingTransfer.Ready(MobileSharedTransfer(validation = MobileShareValidation.InvalidTorrent))

private enum class IncomingKind(val malformed: MobileShareValidation) {
    Text(MobileShareValidation.InvalidLink),
    Magnet(MobileShareValidation.InvalidLink),
    Torrent(MobileShareValidation.InvalidTorrent),
}

private fun Intent.sharedText(): MobileSharedTransfer {
    val value = getCharSequenceExtra(Intent.EXTRA_TEXT)
    return if (value != null && !value.fitsMobileTransferInputLimit()) {
        MobileSharedTransfer(validation = MobileShareValidation.TooLong)
    } else {
        parseMobileSharedTransfer(value?.toString().orEmpty())
    }
}

/** A tapped `magnet:` link; anything else stays visible but invalid so the user can see what arrived. */
internal fun parseMobileMagnetLink(link: String): MobileSharedTransfer {
    if (!link.fitsMobileTransferInputLimit()) return MobileSharedTransfer(validation = MobileShareValidation.TooLong)
    val submission = TransferSubmission.parse(link)
        ?.takeIf { it.value.startsWith("$MAGNET_SCHEME:", ignoreCase = true) }
    return submission?.let { MobileSharedTransfer(it.value) }
        ?: MobileSharedTransfer(link, MobileShareValidation.InvalidLink)
}

/** Shared text becomes one link per line when every link in it is complete; prose stays for the user to fix. */
internal fun parseMobileSharedTransfer(text: String): MobileSharedTransfer {
    if (!text.fitsMobileTransferInputLimit()) return MobileSharedTransfer(validation = MobileShareValidation.TooLong)
    return TransferSubmission.parseAll(text)?.let { MobileSharedTransfer(it.joinLines()) } ?: linksInProse(text)
}

private fun linksInProse(text: String): MobileSharedTransfer {
    val links = text.trim().splitToSequence(SharedWhitespace)
        .filter { it.contains("://") || it.contains("$MAGNET_SCHEME:", ignoreCase = true) }
        .distinct()
        .toList()
    return when {
        links.isEmpty() -> MobileSharedTransfer(text, MobileShareValidation.InvalidLink)
        links.size > MAX_TRANSFER_LINKS -> MobileSharedTransfer(text, MobileShareValidation.TooManyLinks)
        links.none { it.hasAmbiguousProseEnding() } ->
            TransferSubmission.parseAll(links.joinToString("\n"))?.let { MobileSharedTransfer(it.joinLines()) }
                ?: MobileSharedTransfer(text, MobileShareValidation.InvalidLink)
        else -> MobileSharedTransfer(text, MobileShareValidation.InvalidLink)
    }
}

internal fun List<TransferSubmission>.joinLines(): String = joinToString("\n") { it.value }

private fun String.hasAmbiguousProseEnding(): Boolean {
    val ending = codePointBefore(length)
    if (ending <= MAX_ASCII_CODE_POINT) return ending.toChar() in ".,;:!?')]}"
    return when (Character.getType(ending)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }
}

internal fun CharSequence.fitsMobileTransferInputLimit(): Boolean =
    length <= MOBILE_TRANSFER_INPUT_LIMIT && toString().toByteArray(Charsets.UTF_8).size <= MOBILE_TRANSFER_INPUT_LIMIT

internal const val MOBILE_TRANSFER_INPUT_LIMIT = 16 * 1024
private const val MAGNET_SCHEME = "magnet"
private val SharedWhitespace = Regex("[\\s\\p{Z}\\u0085]+")
private const val MAX_ASCII_CODE_POINT = 0x7F
