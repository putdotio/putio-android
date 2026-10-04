package io.putdotio.android.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.putdotio.android.PutioFailure
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.downloads.OfflinePlaybackPositions
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

/** Application-owned so service audio can report after its Activity and task have gone. */
internal class MobilePlaybackReporting(
    private val auth: StateFlow<MobileAuthState>,
    private val scope: CoroutineScope,
    onAuthenticationRequired: suspend (MobileAuthSessionId) -> Unit = {},
    /** Positions of downloaded files, kept on the device when put.io cannot take them. */
    private val offline: OfflinePlaybackPositions? = null,
    write: suspend (Long, Double) -> PlaybackRepositoryResult<Unit>,
) {
    private val mutableSavedPositions = MutableSharedFlow<SavedPlaybackPosition>(
        extraBufferCapacity = SAVED_POSITION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Positions the server accepted, for surfaces that cache `start_from`. Late subscribers see only new saves. */
    val savedPositions: SharedFlow<SavedPlaybackPosition> = mutableSavedPositions

    private val writer = PlaybackPositionWriter(scope) { fileId, seconds ->
        val signedIn = auth.value as? MobileAuthState.SignedIn
        val sessionId = signedIn?.sessionId
        write(fileId, seconds).also { result ->
            if (result is PlaybackRepositoryResult.Success) publishSaved(fileId, seconds)
            signedIn?.let { offline?.afterWrite(it.account.userId, fileId, seconds, result) }
            if (sessionId != null && result is PlaybackRepositoryResult.Failure &&
                result.failure.putioFailure is PutioFailure.AuthenticationRequired
            ) {
                // Rejection clears the session and cancels its writer. It must own a separate job.
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

    /** A position put.io accepted, directly or through the offline sync. */
    fun publishSaved(fileId: Long, seconds: Double) {
        mutableSavedPositions.tryEmit(SavedPlaybackPosition(fileId, seconds))
    }

    /**
     * The confirmed resume setting decides. Settings that never loaded this session, such as on
     * a cold start offline, let a downloaded file report under the setting this device last
     * confirmed; its positions then wait in the offline store. A pending or failed write of the
     * setting still suspends reporting, because the settings themselves did load.
     */
    private fun resumeAllowed(sessionId: MobileAuthSessionId, userId: Long, fileId: Long): Boolean {
        val state = settings?.takeIf { it.sessionId == sessionId }?.state?.value
        val offlineStore = offline?.store(userId)?.takeIf { state?.content !is AccountSettingsContent.Ready }
        return state?.confirmedResumePlayback()
            ?: (offlineStore?.resumeSetting == true && offlineStore.tracks(fileId) && state != null)
    }

    fun observe(player: Player): PlaybackPositionObserver =
        PlaybackPositionObserver(player, scope, writer::offer)

    private data class SettingsBinding(
        val sessionId: MobileAuthSessionId,
        val state: StateFlow<AccountSettingsState>,
    )
}

internal data class SavedPlaybackPosition(val fileId: Long, val seconds: Double)

private const val SAVED_POSITION_BUFFER = 16
