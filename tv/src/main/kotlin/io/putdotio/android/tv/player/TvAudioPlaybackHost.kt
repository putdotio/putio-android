package io.putdotio.android.tv.player

import android.content.Context
import io.putdotio.android.files.FilesItem
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackPlatform
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.playerFailureTelemetry
import io.putdotio.android.playback.putioFailure
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.PutioFailureReports
import io.putdotio.android.tv.TvSessionKey
import io.putdotio.android.tv.answerResumePrompts
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.isUnknown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

internal class TvAudioSessionIdentity(
    val sessionKey: TvSessionKey?,
    val authState: StateFlow<TvAuthState>,
    val application: Context?,
)

internal class TvAudioMedia(
    val target: PlaybackTarget,
    val startItem: FilesItem,
    val fromHome: Boolean,
    val startAnswer: PlaybackEvent,
)

internal class TvAudioSessionCallbacks(
    val playbackRepository: PlaybackRepository,
    val settings: StateFlow<AccountSettingsState>,
    val writePlaybackPosition: suspend (fileId: Long, seconds: Double) -> PlaybackRepositoryResult<Unit>,
    val onSaved: (fileId: Long, seconds: Double) -> Unit,
    val onAuthenticationRejected: () -> Unit,
)

internal class TvAudioStartRequest(
    val identity: TvAudioSessionIdentity,
    val media: TvAudioMedia,
    val callbacks: TvAudioSessionCallbacks,
)

internal class TvAudioSessionAttachments(
    val sessionKey: TvSessionKey?,
    val settings: StateFlow<AccountSettingsState>,
    val onSaved: (fileId: Long, seconds: Double) -> Unit,
    val onAuthenticationRejected: () -> Unit,
)

internal data class TvHostedAudio(
    val sessionKey: TvSessionKey?,
    val controller: PlaybackController,
    val startItem: FilesItem,
    val playingFromHome: Boolean,
    val inBackground: Boolean,
    val queue: TvPlaybackQueue?,
    val reporting: TvPlaybackReporting,
)

internal class TvAudioPlaybackHost {
    private val mutableActive = MutableStateFlow<TvHostedAudio?>(null)
    private val settingsSnapshot = MutableStateFlow(AccountSettingsReducer.start().state)
    private var hostJob: Job? = null
    private var onSaved: ((fileId: Long, seconds: Double) -> Unit)? = null
    private var onAuthRejected: (() -> Unit)? = null
    private var sessionRejected = false

    internal var playerFactory: TvPlayerFactory = DefaultTvPlayerFactory

    val active: StateFlow<TvHostedAudio?> = mutableActive.asStateFlow()

    fun start(request: TvAudioStartRequest): PlaybackController {
        stop()
        val identity = request.identity
        val media = request.media
        val callbacks = request.callbacks
        settingsSnapshot.value = callbacks.settings.value
        onSaved = callbacks.onSaved
        onAuthRejected = callbacks.onAuthenticationRejected
        sessionRejected = false
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)
        hostJob = job
        val controller = PlaybackController(media.target, callbacks.playbackRepository, scope, analytics = true)
        val reporting = TvPlaybackReporting(
            scope = scope,
            settings = settingsSnapshot,
            sessionCurrent = { identity.authState.value.matches(identity.sessionKey) },
            write = callbacks.writePlaybackPosition,
            onSaved = { fileId, seconds -> onSaved?.invoke(fileId, seconds) },
        )
        val queue = identity.application?.let { app ->
            TvPlaybackQueue(
                context = app.applicationContext,
                factory = playerFactory,
                reporter = reporting,
                controller = controller,
                folder = TvFolderLookup(controller.state, callbacks.playbackRepository::findNeighbours) {
                    sessionRejected = true
                    reporting.markAuthenticationRejected()
                    onAuthRejected?.invoke()
                },
                defaultAnalytics = controller.analyticsReporter,
                onFailure = { failure, positionMillis -> reportFailure(controller, failure, positionMillis) },
                onQueueFinished = ::stop,
            ).also(TvPlaybackQueue::start)
        }
        mutableActive.value = TvHostedAudio(
            sessionKey = identity.sessionKey,
            controller = controller,
            startItem = media.startItem,
            playingFromHome = media.fromHome,
            inBackground = false,
            queue = queue,
            reporting = reporting,
        )
        scope.launch { controller.answerResumePrompts(media.startItem.id, media.startAnswer) }
        scope.launch {
            reporting.authenticationRejected.collect { rejected ->
                if (rejected) {
                    sessionRejected = true
                    onAuthRejected?.invoke()
                }
            }
        }
        scope.launch {
            identity.authState.collect { auth -> if (!auth.matches(identity.sessionKey)) stop() }
        }
        return controller
    }

    fun reattach(attachments: TvAudioSessionAttachments): TvHostedAudio? {
        val hosted = mutableActive.value?.takeIf { it.sessionKey == attachments.sessionKey } ?: return null
        settingsSnapshot.value = attachments.settings.value
        onSaved = attachments.onSaved
        onAuthRejected = attachments.onAuthenticationRejected
        if (sessionRejected) attachments.onAuthenticationRejected()
        return hosted
    }

    fun detachCallbacks(savedCallback: (fileId: Long, seconds: Double) -> Unit) {
        if (onSaved === savedCallback) {
            onSaved = null
            onAuthRejected = null
        }
    }

    fun updateSettings(state: AccountSettingsState) {
        settingsSnapshot.value = state
    }

    fun setBackground(inBackground: Boolean) {
        mutableActive.value = mutableActive.value?.copy(inBackground = inBackground)
    }

    fun queueFor(controller: PlaybackController): TvPlaybackQueue? =
        mutableActive.value?.takeIf { it.controller === controller }?.queue

    fun owns(controller: PlaybackController): Boolean =
        mutableActive.value?.controller === controller

    suspend fun <T> supersede(fileId: Long, block: suspend () -> T): T =
        mutableActive.value?.reporting?.supersede(fileId, block) ?: block()

    fun stop() {
        val hosted = mutableActive.getAndUpdate { null } ?: return
        val job = hostJob
        hostJob = null
        onSaved = null
        onAuthRejected = null
        sessionRejected = false
        hosted.queue?.close()
        hosted.reporting.close()
        hosted.controller.close()
        job?.cancel()
    }

    fun resetForTests() {
        stop()
        playerFactory = DefaultTvPlayerFactory
    }
}

private fun reportFailure(controller: PlaybackController, failure: PlaybackFailure, positionMillis: Long) {
    val playing = controller.state.value
    val telemetry = playing.playerFailureTelemetry(failure, PlaybackPlatform.Tv, firstFrameSource = null)
    val shown = controller.dispatch(PlaybackEvent.PlayerFailed(failure, positionMillis)) &&
        controller.state.value.content is PlaybackContent.Failed
    if (shown) {
        val id = PutioDiagnostics.capture(telemetry)
        failure.putioFailure?.takeIf { it.isUnknown }?.let { PutioFailureReports.record(it, id) }
    }
}

private fun TvAuthState.matches(expected: TvSessionKey?): Boolean =
    expected != null &&
        (this as? TvAuthState.SignedIn)?.let { TvSessionKey(it.account.userId, it.sessionId) } == expected
