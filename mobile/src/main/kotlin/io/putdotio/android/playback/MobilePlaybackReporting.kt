package io.putdotio.android.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.putdotio.android.PutioFailure
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.downloads.OfflinePlaybackPositions
import io.putdotio.android.downloads.loadedStore
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.confirmedResumePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class MobilePlaybackReporting(
    private val auth: StateFlow<MobileAuthState>,
    private val scope: CoroutineScope,
    onAuthenticationRequired: suspend (MobileAuthSessionId) -> Unit = {},
    private val offline: () -> OfflinePlaybackPositions? = { null },
    write: suspend (Long, Double) -> PlaybackRepositoryResult<Unit>,
) {
    private val mutableSavedPositions = MutableSharedFlow<SavedPlaybackPosition>(
        extraBufferCapacity = SAVED_POSITION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val savedPositions: SharedFlow<SavedPlaybackPosition> = mutableSavedPositions

    private val writer = PlaybackPositionWriter(scope) { fileId, seconds ->
        val signedIn = auth.value as? MobileAuthState.SignedIn
        val sessionId = signedIn?.sessionId
        write(fileId, seconds).also { result ->
            if (result is PlaybackRepositoryResult.Success) publishSaved(fileId, seconds)
            signedIn?.let { offline()?.afterWrite(it.account.userId, fileId, seconds, result) }
            if (sessionId != null && result is PlaybackRepositoryResult.Failure &&
                result.failure.putioFailure is PutioFailure.AuthenticationRequired
            ) {
                scope.launch {
                    if ((auth.value as? MobileAuthState.SignedIn)?.sessionId == sessionId) {
                        onAuthenticationRequired(sessionId)
                    }
                }
            }
        }
    }
    private var settings: SettingsBinding? = null
    private var settingsJob: Job? = null

    init {
        scope.launch {
            auth.collect {
                if ((it as? MobileAuthState.SignedIn)?.sessionId != settings?.sessionId) {
                    settingsJob?.cancel()
                    settingsJob = null
                    settings = null
                }
                writer.reconcile()
            }
        }
    }

    fun factoryFor(
        sessionId: MobileAuthSessionId,
        settingsState: StateFlow<AccountSettingsState>,
        delegate: MobilePlayerFactory,
    ): MobilePlayerFactory {
        if ((auth.value as? MobileAuthState.SignedIn)?.sessionId == sessionId &&
            (settings?.sessionId != sessionId || settings?.state !== settingsState)
        ) {
            settingsJob?.cancel()
            settings = SettingsBinding(sessionId, settingsState)
            settingsJob = scope.launch { settingsState.collect { writer.reconcile() } }
        }
        return object : MobilePlayerFactory by delegate {
            override fun reportableItem(item: MediaItem, useStartFrom: Boolean): MediaItem {
                val fileId = item.mediaId.toLongOrNull()?.takeIf { it > 0L }
                if (fileId == null || !useStartFrom ||
                    (auth.value as? MobileAuthState.SignedIn)?.sessionId != sessionId
                ) {
                    return item
                }
                val token = writer.register(fileId) {
                    val signedIn = auth.value as? MobileAuthState.SignedIn
                    signedIn?.sessionId == sessionId && resumeAllowed(sessionId, signedIn.account.userId, fileId)
                }
                return item.withReportingLease(token)
            }
        }
    }

    fun publishSaved(fileId: Long, seconds: Double) {
        mutableSavedPositions.tryEmit(SavedPlaybackPosition(fileId, seconds))
    }

    private fun resumeAllowed(sessionId: MobileAuthSessionId, userId: Long, fileId: Long): Boolean {
        val state = settings?.takeIf { it.sessionId == sessionId }?.state?.value
        val offlineStore = offline()?.loadedStore(userId)?.takeIf { state?.content !is AccountSettingsContent.Ready }
        return state?.confirmedResumePlayback()
            ?: (offlineStore?.resumeSetting == true && offlineStore.tracks(fileId) && state != null)
    }

    suspend fun exclusive(block: suspend () -> Unit) = writer.exclusive(block)

    suspend fun <T> supersede(fileId: Long, block: suspend () -> T): T = writer.supersede(fileId, block)

    suspend fun <T> supersede(fileIds: Iterable<Long>, block: suspend () -> T): T = writer.supersede(fileIds, block)

    fun observe(player: Player): PlaybackPositionObserver =
        PlaybackPositionObserver(player, scope, writer::offer)

    private data class SettingsBinding(
        val sessionId: MobileAuthSessionId,
        val state: StateFlow<AccountSettingsState>,
    )
}

internal data class SavedPlaybackPosition(val fileId: Long, val seconds: Double)

private const val SAVED_POSITION_BUFFER = 16
