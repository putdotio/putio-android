package io.putdotio.android.transfers

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

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
    val kind = when {
        action == Intent.ACTION_SEND && type == "text/plain" -> IncomingKind.Text
        viewedUri?.scheme.equals(MAGNET_SCHEME, ignoreCase = true) -> IncomingKind.Magnet
        // The manifest routes VIEW content URIs here only for the BitTorrent type.
        viewedUri?.scheme == ContentResolver.SCHEME_CONTENT -> IncomingKind.Torrent
        action == Intent.ACTION_SEND && type == TORRENT_MEDIA_TYPE -> IncomingKind.Torrent
        else -> return null
    }
    return try {
        when (kind) {
            IncomingKind.Text -> MobileIncomingTransfer.Ready(sharedText())
            IncomingKind.Magnet -> MobileIncomingTransfer.Ready(parseMobileMagnetLink(viewedUri.toString()))
            IncomingKind.Torrent ->
                (viewedUri ?: IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
                    ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
                    ?.let(MobileIncomingTransfer::Torrent)
                    ?: MobileIncomingTransfer.Ready(MobileSharedTransfer(validation = MobileShareValidation.InvalidTorrent))
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
    TransferSubmission.parseAll(text)?.let { links -> return MobileSharedTransfer(links.joinLines()) }
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

/**
 * Reads a shared `.torrent`; failures become a draft error instead of an exception. URIs on this app's
 * own providers are refused: the read runs as this app, so a caller could otherwise nominate files it
 * cannot open itself.
 */
// The provider is another app's code; any runtime exception it throws is that app's failure, not ours.
@Suppress("TooGenericExceptionCaught")
internal fun ContentResolver.readMobileTorrent(uri: Uri, ownPackage: String): MobileSharedTransfer {
    if (isOwnProviderAuthority(uri.authority, ownPackage)) {
        return MobileSharedTransfer(validation = MobileShareValidation.InvalidTorrent)
    }
    return try {
        val name = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
        readMobileTorrent(name ?: uri.lastPathSegment) { openInputStream(uri) }
    } catch (_: RuntimeException) {
        MobileSharedTransfer(validation = MobileShareValidation.InvalidTorrent)
    }
}

/** Android drops a `<userId>@` prefix when it resolves a provider, so ownership ignores it too. */
internal fun isOwnProviderAuthority(authority: String?, ownPackage: String): Boolean {
    val resolved = authority?.substringAfterLast('@') ?: return true
    return resolved == ownPackage || resolved.startsWith("$ownPackage.")
}

internal fun readMobileTorrent(displayName: String?, open: () -> InputStream?): MobileSharedTransfer {
    val bytes = try {
        open()?.use { it.readBounded(MAX_TORRENT_BYTES) }
    } catch (_: IOException) {
        null
    } ?: return MobileSharedTransfer(validation = MobileShareValidation.InvalidTorrent)
    return when {
        bytes.size > MAX_TORRENT_BYTES -> MobileSharedTransfer(validation = MobileShareValidation.TorrentTooLarge)
        !bytes.looksLikeTorrent() -> MobileSharedTransfer(validation = MobileShareValidation.InvalidTorrent)
        else -> MobileSharedTransfer(torrent = TorrentUpload(torrentFileName(displayName), bytes))
    }
}

/** Returns at most [limit] + 1 bytes so an oversized stream is detected without reading it all. */
private fun InputStream.readBounded(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
    while (buffer.size() <= limit) {
        // A superseded read is interrupted; stop between chunks instead of holding up to the limit.
        if (Thread.interrupted()) throw InterruptedIOException()
        val read = read(chunk, 0, minOf(chunk.size, limit + 1 - buffer.size()))
        if (read < 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

// A metainfo file is a bencoded dictionary with an `info` key; put.io validates the rest.
private fun ByteArray.looksLikeTorrent(): Boolean =
    size >= MIN_TORRENT_BYTES && first() == 'd'.code.toByte() && last() == 'e'.code.toByte() &&
        String(this, Charsets.ISO_8859_1).contains("4:info")

/** put.io starts a transfer only for a `.torrent` name, so the name always ends with one. */
internal fun torrentFileName(displayName: String?): String {
    val base = displayName.orEmpty()
        .substringAfterLast('/')
        .filterNot { it.isISOControl() || it == '\\' }
        .trim()
        .removeSuffixIgnoringCase(TorrentUpload.TORRENT_EXTENSION)
        .take(MAX_TORRENT_NAME_LENGTH)
        .trim()
    return base.ifEmpty { DEFAULT_TORRENT_NAME } + TorrentUpload.TORRENT_EXTENSION
}

private fun String.removeSuffixIgnoringCase(suffix: String): String =
    if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

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
internal const val MAX_TORRENT_BYTES = 16 * 1024 * 1024
private const val MIN_TORRENT_BYTES = 8
private const val MAX_TORRENT_NAME_LENGTH = 200
private const val DEFAULT_TORRENT_NAME = "Transfer"
private const val MAGNET_SCHEME = "magnet"
private val SharedWhitespace = Regex("[\\s\\p{Z}\\u0085]+")
private const val MAX_ASCII_CODE_POINT = 0x7F
