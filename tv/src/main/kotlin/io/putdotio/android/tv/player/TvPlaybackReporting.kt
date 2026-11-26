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

internal interface TvPlaybackReporter {
    fun lease(fileId: Long): String?

    fun observe(player: Player): Closeable

    companion object {
        val None: TvPlaybackReporter = object : TvPlaybackReporter {
            override fun lease(fileId: Long): String? = null

            override fun observe(player: Player) = Closeable {}
        }
    }
}

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

    val authenticationRejected: StateFlow<Boolean> = mutableRejected.asStateFlow()

    private val writer: PlaybackPositionWriter = PlaybackPositionWriter(scope) { fileId, seconds ->
        write(fileId, seconds).also { result ->
            when {
                closed -> Unit
                result is PlaybackRepositoryResult.Success -> onSaved(fileId, seconds)
                (result as? PlaybackRepositoryResult.Failure)?.failure?.putioFailure is
                    PutioFailure.AuthenticationRequired ->
                    if (sessionCurrent()) {
                        mutableRejected.value = true
                        writer.reconcile()
                    }
            }
        }
    }
    private val settingsJob = scope.launch { settings.collect { writer.reconcile() } }

    fun startPlayback() {
        current = null
    }

    override fun lease(fileId: Long): String? {
        if (closed) return null
        return current?.takeIf { it.fileId == fileId }?.token
            ?: writer.register(fileId, ::authorized).also { current = Lease(fileId, it) }
    }

    suspend fun <T> supersede(fileId: Long, block: suspend () -> T): T = writer.supersede(fileId, block)

    override fun observe(player: Player): Closeable =
        if (closed) Closeable {} else PlaybackPositionObserver(player, scope, writer::offer)

    internal fun markAuthenticationRejected() {
        if (!closed && sessionCurrent()) {
            mutableRejected.value = true
            writer.reconcile()
        }
    }

    override fun close() {
        closed = true
        current = null
        settingsJob.cancel()
        writer.close()
    }

    private fun authorized(): Boolean {
        if (closed || mutableRejected.value) return false
        return sessionCurrent() && settings.value.confirmedResumePlayback() == true
    }

    private data class Lease(val fileId: Long, val token: String)
}
