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
import io.putdotio.android.settings.AccountSettingsContent
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
    private var settingsSessionKey: TvSessionKey? = null
    private var hostJob: Job? = null
    private var sessionReporting: SessionReporting? = null
    private var onSaved: ((fileId: Long, seconds: Double) -> Unit)? = null
    private var onAuthRejected: (() -> Unit)? = null
    private var sessionRejected = false

    internal var playerFactory: TvPlayerFactory = DefaultTvPlayerFactory

    val active: StateFlow<TvHostedAudio?> = mutableActive.asStateFlow()

    fun start(request: TvAudioStartRequest): PlaybackController {
        val identity = request.identity
        val media = request.media
        val callbacks = request.callbacks
        val retained = sessionReporting?.takeIf { it.matches(identity) }
        stop(cancelWrites = retained == null)
        TvBackgroundSessions.markAppForeground()
        onSaved = callbacks.onSaved
        onAuthRejected = callbacks.onAuthenticationRejected
        val onRejected: () -> Unit = {
            sessionRejected = true
            val notify = onAuthRejected
            if (notify != null) notify() else stop(cancelWrites = false)
        }
        val reporting = if (retained != null) {
            retained.writePosition = callbacks.writePlaybackPosition
            retained.reporting.also { it.startPlayback() }
        } else {
            settingsSessionKey = identity.sessionKey
            settingsSnapshot.value = callbacks.settings.value
            sessionRejected = false
            createSessionReporting(
                request = request,
                settings = settingsSnapshot,
                onSaved = { fileId, seconds -> onSaved?.invoke(fileId, seconds) },
                onRejected = onRejected,
                onSessionEnded = { stop(cancelWrites = true) },
            ).also { sessionReporting = it }.reporting
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)
        hostJob = job
        val controller = PlaybackController(media.target, callbacks.playbackRepository, scope, analytics = true)
        val queue = createAudioQueue(request, playerFactory, reporting, controller) { rejected ->
            if (rejected) {
                reporting.markAuthenticationRejected()
                onRejected()
            } else {
                stop()
            }
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
        if (retained != null) updateSettings(identity.sessionKey, callbacks.settings.value)
        scope.launch { controller.answerResumePrompts(media.startItem.id, media.startAnswer) }
        return controller
    }

    fun reattach(attachments: TvAudioSessionAttachments): TvHostedAudio? {
        val hosted = mutableActive.value?.takeIf { it.sessionKey == attachments.sessionKey }
        if (hosted != null || sessionReporting?.sessionKey == attachments.sessionKey) {
            updateSettings(attachments.sessionKey, attachments.settings.value)
            onSaved = attachments.onSaved
            onAuthRejected = attachments.onAuthenticationRejected
            if (sessionRejected) attachments.onAuthenticationRejected()
        }
        return hosted
    }

    fun detachCallbacks(savedCallback: (fileId: Long, seconds: Double) -> Unit) {
        if (onSaved === savedCallback) {
            onSaved = null
            onAuthRejected = null
        }
    }

    fun updateSettings(sessionKey: TvSessionKey?, state: AccountSettingsState) {
        if (sessionKey == null) return
        val ownsActive = mutableActive.value?.sessionKey == sessionKey
        val ownsReporting = sessionReporting?.sessionKey == sessionKey
        if (!ownsActive && !ownsReporting) return
        if (shouldApplySettings(settingsSessionKey, settingsSnapshot.value, sessionKey, state)) {
            settingsSessionKey = sessionKey
            settingsSnapshot.value = state
        }
    }

    fun setBackground(inBackground: Boolean) {
        mutableActive.value = mutableActive.value?.copy(inBackground = inBackground)
    }

    fun queueFor(controller: PlaybackController): TvPlaybackQueue? =
        mutableActive.value?.takeIf { it.controller === controller }?.queue

    fun owns(controller: PlaybackController): Boolean =
        mutableActive.value?.controller === controller

    suspend fun <T> supersede(fileId: Long, block: suspend () -> T): T =
        sessionReporting?.reporting?.supersede(fileId, block) ?: block()

    fun stop(cancelWrites: Boolean = false) {
        if (cancelWrites) {
            onSaved = null
            onAuthRejected = null
            sessionRejected = false
            settingsSessionKey = null
            settingsSnapshot.value = AccountSettingsReducer.start().state
            sessionReporting?.close()
            sessionReporting = null
        }
        val hosted = mutableActive.getAndUpdate { null }
        val job = hostJob
        hostJob = null
        hosted?.queue?.close()
        hosted?.controller?.close()
        job?.cancel()
    }

    fun resetForTests() {
        stop(cancelWrites = true)
        playerFactory = DefaultTvPlayerFactory
    }
}

private class SessionReporting(
    val sessionKey: TvSessionKey?,
    val authState: StateFlow<TvAuthState>,
    val job: Job,
    val reporting: TvPlaybackReporting,
    var writePosition: suspend (fileId: Long, seconds: Double) -> PlaybackRepositoryResult<Unit>,
) {
    fun matches(identity: TvAudioSessionIdentity): Boolean =
        sessionKey == identity.sessionKey && authState === identity.authState

    fun close() {
        reporting.close()
        job.cancel()
    }
}

private fun createSessionReporting(
    request: TvAudioStartRequest,
    settings: StateFlow<AccountSettingsState>,
    onSaved: (Long, Double) -> Unit,
    onRejected: () -> Unit,
    onSessionEnded: () -> Unit,
): SessionReporting {
    val identity = request.identity
    val job = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.Main.immediate + job)
    lateinit var created: SessionReporting
    val reporting = TvPlaybackReporting(
        scope = scope,
        settings = settings,
        sessionCurrent = { identity.authState.value.matches(identity.sessionKey) },
        write = { fileId, seconds -> created.writePosition(fileId, seconds) },
        onSaved = onSaved,
    )
    created = SessionReporting(
        sessionKey = identity.sessionKey,
        authState = identity.authState,
        job = job,
        reporting = reporting,
        writePosition = request.callbacks.writePlaybackPosition,
    )
    scope.launch { reporting.authenticationRejected.collect { rejected -> if (rejected) onRejected() } }
    scope.launch { identity.authState.collect { auth -> if (!auth.matches(identity.sessionKey)) onSessionEnded() } }
    return created
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

private fun shouldApplySettings(
    currentKey: TvSessionKey?,
    current: AccountSettingsState,
    incomingKey: TvSessionKey,
    incoming: AccountSettingsState,
): Boolean =
    currentKey != incomingKey ||
        current.content !is AccountSettingsContent.Ready ||
        incoming.content is AccountSettingsContent.Ready

private fun createAudioQueue(
    request: TvAudioStartRequest,
    factory: TvPlayerFactory,
    reporting: TvPlaybackReporting,
    controller: PlaybackController,
    onTerminate: (rejected: Boolean) -> Unit,
): TvPlaybackQueue? =
    request.identity.application?.let { app ->
        TvPlaybackQueue(
            context = app.applicationContext,
            factory = factory,
            reporter = reporting,
            controller = controller,
            folder = TvFolderLookup(controller.state, request.callbacks.playbackRepository::findNeighbours) {
                onTerminate(true)
            },
            defaultAnalytics = controller.analyticsReporter,
            onFailure = { failure, positionMillis -> reportFailure(controller, failure, positionMillis) },
            onQueueFinished = { onTerminate(false) },
        ).also(TvPlaybackQueue::start)
    }
