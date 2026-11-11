package io.putdotio.android.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.InterruptedIOException

public class StallGate(internal val stallAfterBytes: Long) {
    private val lock = Object()

    @Volatile
    internal var open: Boolean = false
        private set

    public fun release() {
        synchronized(lock) {
            open = true
            lock.notifyAll()
        }
    }

    internal fun await() {
        synchronized(lock) {
            while (!open) {
                try {
                    lock.wait()
                } catch (interrupted: InterruptedException) {
                    throw InterruptedIOException("Stall gate interrupted").apply { initCause(interrupted) }
                }
            }
        }
    }
}

@UnstableApi
public class StallingDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val gate: StallGate,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = StallingDataSource(upstream.createDataSource(), gate)
}

@UnstableApi
private class StallingDataSource(
    private val upstream: DataSource,
    private val gate: StallGate,
) : DataSource by upstream {
    private var position = 0L

    override fun open(dataSpec: DataSpec): Long {
        position = dataSpec.position
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!gate.open && position >= gate.stallAfterBytes) gate.await()
        val allowed = if (gate.open) length else minOf(length.toLong(), gate.stallAfterBytes - position).toInt()
        val read = upstream.read(buffer, offset, allowed)
        if (read > 0) position += read
        return read
    }
}
