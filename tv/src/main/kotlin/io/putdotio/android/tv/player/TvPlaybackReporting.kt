package io.putdotio.android.tv.player

import androidx.annotation.MainThread
import androidx.media3.common.Player
import io.putdotio.android.PutioFailure
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackPositionObserver
import io.putdotio.android.playback.PlaybackPositionWriter
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.putioFailure
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.confirmedResumePlayback
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How one TV playback screen writes its positions back. */
internal interface TvPlaybackReporter {
    /** The lease the item's positions are written under, or null when this playback must not write. */
    fun lease(fileId: Long): String?

    /** Starts observing [player]; closing the result captures its last position. */
    fun observe(player: Player): Closeable

    companion object {
        val None: TvPlaybackReporter = object : TvPlaybackReporter {
            override fun lease(fileId: Long): String? = null

            override fun observe(player: Player) = Closeable {}
        }
    }
}

/**
 * The session's start-from write-back, on the same writer and observer mobile uses: a sample
 * every 15 s of playback plus pause, stop, end, error and exit, one request in flight and one
 * latest snapshot pending, never a write per progress tick. Writes need the session to still be
 * the signed-in one and the account's resume setting confirmed on; anything else discards them.
 */
@MainThread
internal class TvPlaybackReporting(
    private val scope: CoroutineScope,
    private val settings: StateFlow<AccountSettingsState>,
    private val sessionCurrent: () -> Boolean,
    write: suspend (fileId: Long, seconds: Double) -> PlaybackRepositoryResult<Unit>,
    private val onSaved: (fileId: Long, seconds: Double) -> Unit,
) : TvPlaybackReporter, Closeable {
    private var closed = false
    private var current: Lease? = null
    private val mutableRejected = MutableStateFlow(false)

    /** A write was refused for the session's credential. Sticky: the session is over. */
    val authenticationRejected: StateFlow<Boolean> = mutableRejected.asStateFlow()

    private val writer = PlaybackPositionWriter(scope) { fileId, seconds ->
        write(fileId, seconds).also { result ->
            when {
                closed -> Unit
                result is PlaybackRepositoryResult.Success -> onSaved(fileId, seconds)
                // A verdict that lands after another session took over is not this session's.
                (result as? PlaybackRepositoryResult.Failure)?.failure?.putioFailure is
                    PutioFailure.AuthenticationRequired ->
                    if (sessionCurrent()) mutableRejected.value = true
            }
        }
    }
    private val settingsJob = scope.launch { settings.collect { writer.reconcile() } }

    /**
     * A new playback: its item gets a fresh lease. A player rebuilt for the same playback
     * (activity recreation) keeps the lease, so its predecessor's exit write still lands.
     */
    fun startPlayback() {
        current = null
    }

    override fun lease(fileId: Long): String? {
        if (closed) return null
        return current?.takeIf { it.fileId == fileId }?.token
            ?: writer.register(fileId, ::authorized).also { current = Lease(fileId, it) }
    }

    override fun observe(player: Player): Closeable =
        if (closed) Closeable {} else PlaybackPositionObserver(player, scope, writer::offer)

    override fun close() {
        closed = true
        current = null
        settingsJob.cancel()
        writer.close()
    }

    private fun authorized(): Boolean =
        !closed && sessionCurrent() && settings.value.confirmedResumePlayback() == true

    private data class Lease(val fileId: Long, val token: String)
}
