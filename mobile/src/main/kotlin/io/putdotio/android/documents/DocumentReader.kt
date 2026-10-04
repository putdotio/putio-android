package io.putdotio.android.documents

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import io.putdotio.android.auth.MobileSessionKey
import java.io.IOException
import java.io.InputStream

/**
 * Serves one opened document to a proxy file descriptor. A read that continues where the last one stopped keeps the
 * open stream, a short jump ahead skips on it, and any other offset reopens the bytes there. The reader belongs to
 * the session that opened it: once that session ends, or [revoke] runs, every read fails.
 */
internal class DocumentReader(
    private val bytes: DocumentBytes,
    val session: MobileSessionKey,
    private val isCurrent: () -> Boolean,
    /** Runs once the descriptor's last holder closes it. */
    private val onReleased: () -> Unit,
) : ProxyFileDescriptorCallback() {
    @Volatile
    private var revoked = false

    @Volatile
    private var opened: OpenedBytes? = null
    private var position = 0L

    val onDevice: Boolean get() = bytes.onDevice

    override fun onGetSize(): Long = bytes.size

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        ensureReadable()
        if (offset >= bytes.size || size <= 0) return 0
        val wanted = minOf(size.toLong(), bytes.size - offset).toInt()
        return try {
            val count = streamAt(offset).readUpTo(data, wanted)
            position += count
            ensureReadable()
            count
        } catch (error: IOException) {
            closeStream()
            // A read cut short by the session ending reports the session, not the I/O it interrupted.
            ensureReadable()
            throw ErrnoException("read", OsConstants.EIO, error)
        }
    }

    override fun onRelease() {
        closeStream()
        onReleased()
    }

    /** The session ended: a read blocked on the network stops now, and nothing reads again. */
    fun revoke() {
        revoked = true
        opened?.interrupt?.invoke()
    }

    private fun streamAt(offset: Long): InputStream {
        val current = opened
        if (current != null && offset >= position && offset - position <= SKIP_LIMIT) {
            current.input.skipFully(offset - position)
            position = offset
            return current.input
        }
        closeStream()
        val next = bytes.open(offset)
        opened = next
        position = offset
        // revoke() may have run while the open blocked, before it could interrupt this stream.
        ensureReadable()
        return next.input
    }

    /** A refused read also releases the stream, so an ended session holds no connection open. */
    private fun ensureReadable() {
        if (revoked || !isCurrent()) {
            closeStream()
            throw ErrnoException("read", OsConstants.EACCES)
        }
    }

    private fun closeStream() {
        val current = opened ?: return
        opened = null
        runCatching { current.input.close() }
    }

    private companion object {
        /** Forward gaps up to this size read through the open stream rather than starting a new request. */
        const val SKIP_LIMIT = 256L * 1024L
    }
}

/** Fills up to [wanted] bytes, stopping early only at the end of the stream; a short read would read as EOF. */
internal fun InputStream.readUpTo(data: ByteArray, wanted: Int): Int {
    var count = 0
    while (count < wanted) {
        val read = read(data, count, wanted - count)
        if (read < 0) break
        count += read
    }
    return count
}

internal fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else if (read() < 0) {
            throw IOException("Stream ended $remaining bytes before the requested offset")
        } else {
            remaining -= 1L
        }
    }
}
