package io.putdotio.android.transfers

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

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
        containsBytes(INFO_KEY)

private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

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

internal const val MAX_TORRENT_BYTES = 16 * 1024 * 1024
private const val MIN_TORRENT_BYTES = 8
private val INFO_KEY = "4:info".toByteArray(Charsets.US_ASCII)
private const val MAX_TORRENT_NAME_LENGTH = 200
private const val DEFAULT_TORRENT_NAME = "Transfer"
