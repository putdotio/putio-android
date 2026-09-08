package io.putdotio.android.playback

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.UUID

/** All access belongs to the application's main thread, including the final authorization check. */
@MainThread
internal class PlaybackPositionWriter(
    private val scope: CoroutineScope,
    private val write: suspend (Long, Double) -> PlaybackRepositoryResult<Unit>,
) : Closeable {
    private val leases = linkedMapOf<String, Lease>()
    private var pending: Snapshot? = null
    private var active: Snapshot? = null
    private var activeJob: Job? = null
    private var closed = false
    private val mutableFailure = MutableStateFlow<PlaybackFailure?>(null)
    val failure = mutableFailure.asStateFlow()

    fun register(fileId: Long, authorized: () -> Boolean): String {
        check(!closed)
        require(fileId > 0L)
        // A reopened file supersedes its old producer, including a queued exit snapshot.
        leases.entries.removeAll { it.value.fileId == fileId }
        while (leases.size >= MAX_LEASES) leases.remove(leases.keys.first())
        val token = UUID.randomUUID().toString()
        leases[token] = Lease(fileId, authorized)
        reconcile()
        return token
    }

    fun offer(token: String, positionMillis: Long) {
        val lease = leases[token]?.takeIf { !closed && positionMillis > 0L && it.authorized() } ?: return
        if (lease.lastOffered?.div(POSITION_DEDUP_MILLIS) == positionMillis / POSITION_DEDUP_MILLIS) return
        lease.lastOffered = positionMillis
        // One in-flight request and one latest snapshot bound memory during slow requests
        // and rapid file switches. Intermediate queued exit positions may be superseded.
        pending = Snapshot(token, lease.fileId, positionMillis)
        drain()
    }

    /** Discards revoked work; this is deliberately different from a player's trailing flush. */
    fun reconcile() {
        leases.values.filterNot { it.authorized() }.forEach { it.lastOffered = null }
        if (pending?.allowed() == false) pending = null
        if (active?.allowed() == false) activeJob?.cancel()
        mutableFailure.value = null
    }

    override fun close() {
        closed = true
        leases.clear()
        pending = null
        activeJob?.cancel()
        mutableFailure.value = null
    }

    private fun Snapshot.allowed(): Boolean = !closed && leases[token]?.authorized?.invoke() == true

    @Suppress("TooGenericExceptionCaught")
    private fun drain() {
        if (closed || activeJob != null) return
        val snapshot = pending ?: return
        pending = null
        active = snapshot
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (snapshot.allowed()) {
                    // The SDK captures the current credential before suspending. Do not add
                    // a dispatcher hop between authorization and this asynchronous request.
                    val result = withTimeout(WRITE_TIMEOUT_MILLIS) {
                        if (snapshot.allowed()) write(snapshot.fileId, snapshot.positionMillis / MILLIS_PER_SECOND)
                        else null
                    }
                    if (snapshot.allowed()) {
                        mutableFailure.value = (result as? PlaybackRepositoryResult.Failure)?.failure
                    }
                }
            } catch (error: TimeoutCancellationException) {
                if (snapshot.allowed()) mutableFailure.value = PlaybackFailure.NetworkUnavailable(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (snapshot.allowed()) mutableFailure.value = PlaybackFailure.Unexpected(error)
            }
        }
        activeJob = job
        job.invokeOnCompletion {
            if (activeJob === job) {
                active = null
                activeJob = null
                drain()
            }
        }
        job.start()
    }

    private class Lease(val fileId: Long, val authorized: () -> Boolean, var lastOffered: Long? = null)
    private data class Snapshot(val token: String, val fileId: Long, val positionMillis: Long)
}

private const val MAX_LEASES = 8
private const val WRITE_TIMEOUT_MILLIS = 15_000L
private const val MILLIS_PER_SECOND = 1_000.0
private const val POSITION_DEDUP_MILLIS = 1_000L
