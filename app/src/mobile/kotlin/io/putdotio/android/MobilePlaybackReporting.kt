package io.putdotio.android

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.playback.PlaybackPositionWriter
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.confirmedResumePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Application-owned so service audio can report after its Activity and task have gone. */
internal class MobilePlaybackReporting(
    private val auth: StateFlow<MobileAuthState>,
    private val scope: CoroutineScope,
    onAuthenticationRequired: suspend (MobileAuthSessionId) -> Unit = {},
    write: suspend (Long, Double) -> PlaybackRepositoryResult<Unit>,
) {
    private val writer = PlaybackPositionWriter(scope) { fileId, seconds ->
        val sessionId = (auth.value as? MobileAuthState.SignedIn)?.sessionId
        write(fileId, seconds).also { result ->
            if (sessionId != null && result is PlaybackRepositoryResult.Failure &&
                result.failure is PlaybackFailure.AuthenticationRequired
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
                val fileId = item.mediaId.toLongOrNull()?.takeIf { it > 0L } ?: return item
                if (!useStartFrom || (auth.value as? MobileAuthState.SignedIn)?.sessionId != sessionId) return item
                val token = writer.register(fileId) {
                    (auth.value as? MobileAuthState.SignedIn)?.sessionId == sessionId &&
                        settings?.takeIf { it.sessionId == sessionId }?.state?.value?.confirmedResumePlayback() == true
                }
                val extras = Bundle(item.mediaMetadata.extras ?: Bundle())
                extras.putString(PLAYBACK_REPORTING_LEASE_KEY, token)
                return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
            }
        }
    }

    fun observe(player: Player): MobilePlayerPositionObserver =
        MobilePlayerPositionObserver(player, scope, writer::offer)

    private data class SettingsBinding(
        val sessionId: MobileAuthSessionId,
        val state: StateFlow<AccountSettingsState>,
    )
}
