package io.putdotio.android.tv.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.putdotio.android.PutioFailure
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackAnalyticsReporter
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackFindNeighbours
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackPlatform
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.originalFallbackTelemetry
import io.putdotio.android.playback.playerFailureTelemetry
import io.putdotio.android.playback.readySource
import io.putdotio.android.playback.putioFailure
import io.putdotio.android.playback.rememberFolderNeighbours
import io.putdotio.android.playback.rememberNextEpisode
import io.putdotio.android.playback.rememberUpNext
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.PutioFailureReports
import io.putdotio.android.tv.TvBrief
import io.putdotio.android.tv.TvBriefShown
import io.putdotio.android.tv.TvSnackbarHost
import io.putdotio.android.tv.TvSnackbarState
import io.putdotio.android.tv.isUnknown
import io.putdotio.android.tv.files.hasVlc
import io.putdotio.sdk.files.PlaybackSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
internal fun TvPlaybackLayer(
    playing: Boolean,
    player: @Composable () -> Unit,
    background: Boolean = false,
    shell: @Composable () -> Unit,
) {
    val saveableState = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        if (!playing || background) saveableState.SaveableStateProvider(SHELL_STATE_KEY, shell)
        if (playing) {
            LaunchedEffect(background) {
                if (!background) ProductAnalytics.capture(AnalyticsEvent.ScreenViewed(AnalyticsEvent.Screen.Player))
            }
            player()
        }
    }
}

@Composable
internal fun TvPlaybackRoute(
    controller: PlaybackController,
    onExit: () -> Unit,
    onSessionRejected: suspend () -> Unit,
    playerFactory: TvPlayerFactory = DefaultTvPlayerFactory,
    reporter: TvPlaybackReporter = TvPlaybackReporter.None,
    subtitleStartupPolicy: SubtitleStartupPolicy? = null,
    autoplayNextVideo: Boolean = false,
    findNextEpisode: (suspend (PlaybackTarget) -> PlaybackNextResult)? = null,
    findUpNext: PlaybackFindNeighbours? = null,
    openInVlc: (suspend (PlaybackTarget) -> Int?)? = null,
    background: Boolean = false,
    onBackground: (() -> Unit)? = null,
    onForeground: () -> Unit = {},
    miniPlayer: TvMiniPlayerState? = null,
    analytics: PlaybackAnalyticsReporter = controller.analyticsReporter,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val nextEpisode = rememberNextEpisode(state, findNextEpisode, onSessionRejected)
    val upNext = rememberUpNext(state, findUpNext.takeIf { autoplayNextVideo }, onSessionRejected)
    val context = LocalContext.current
    var firstFrameSource by remember { mutableStateOf<PlaybackSource?>(null) }
    val onPlayerFailure = { failure: PlaybackFailure, positionMillis: Long ->
        val playing = controller.state.value
        val telemetry = playing.playerFailureTelemetry(failure, PlaybackPlatform.Tv, firstFrameSource)
        if (controller.showsPlayerFailure(failure, positionMillis)) {
            val id = PutioDiagnostics.capture(telemetry)
            failure.putioFailure?.takeIf { it.isUnknown }?.let { PutioFailureReports.record(it, id) }
        }
    }
    val queue = rememberPlaybackQueue(
        backgrounds = onBackground != null,
        mediaType = state.target.mediaType,
        controller = controller,
        playerFactory = playerFactory,
        reporter = reporter,
        findUpNext = findUpNext,
        onSessionRejected = onSessionRejected,
        analytics = analytics,
        onPlayerFailure = onPlayerFailure,
        onStopRequested = onExit,
    )
    val neighbours = rememberNeighbours(state, queue, findUpNext, onSessionRejected)
    val vlcScope = rememberCoroutineScope()
    var vlcNotice by remember { mutableStateOf<Int?>(null) }
    var vlcHandoff by remember { mutableStateOf<Job?>(null) }
    val snackbar = remember { TvSnackbarState() }
    TvBriefShown(vlcNotice?.let { TvBrief(stringResource(it)) { vlcNotice = null } }, snackbar)
    val failure = when (val content = state.content) {
        is PlaybackContent.Failed -> content.failure
        is PlaybackContent.NextFailed -> content.failure
        else -> null
    }
    val sessionRejected = failure?.putioFailure is PutioFailure.AuthenticationRequired
    LaunchedEffect(sessionRejected) { if (sessionRejected) onSessionRejected() }
    val vlcInstalled = remember(context) { context.hasVlc() }
    val handOffToVlc = openInVlc?.let { open ->
        {
            if (vlcHandoff?.isActive != true) {
                val target = state.target
                vlcHandoff = vlcScope.launch { vlcNotice = open(target) }
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        TvPlayerScreen(
            state = state,
            onBack = onExit,
            onRetry = { controller.dispatch(PlaybackEvent.Retry) },
            onPlayerFailure = onPlayerFailure,
            onFirstFrame = { firstFrameSource = controller.state.value.readySource },
            onOriginalRejected = { reason, positionMillis ->
                val telemetry = controller.state.value.originalFallbackTelemetry(reason, PlaybackPlatform.Tv)
                controller.dispatch(PlaybackEvent.OriginalRejected(reason, positionMillis)).also { consumed ->
                    if (consumed) telemetry?.let(PutioDiagnostics::capture)
                }
            },
            onOriginalChosen = { controller.dispatch(PlaybackEvent.OriginalChosen(it)) },
            onRefreshConversion = { controller.dispatch(PlaybackEvent.RefreshConversion) },
            onStartConversion = { controller.dispatch(PlaybackEvent.StartConversion) },
            playerFactory = playerFactory,
            reporter = queue.activeReporter(reporter),
            subtitleStartupPolicy = subtitleStartupPolicy,
            autoplayNextVideo = autoplayNextVideo,
            onPlaybackEnded = { controller.dispatch(PlaybackEvent.PlayerEnded) },
            nextEpisode = nextEpisode,
            onNextEpisode = { nextEpisode?.let { controller.dispatch(PlaybackEvent.NextChosen(it)) } },
            onUpNext = { controller.dispatch(PlaybackEvent.NextChosen(it)) },
            analytics = analytics,
            upNext = upNext,
            onOpenInVlc = handOffToVlc,
            onPlayInVlc = handOffToVlc?.takeIf { vlcInstalled },
            neighbours = neighbours,
            background = background,
            onBackground = onBackground,
            onForeground = onForeground,
            miniPlayer = miniPlayer,
            notices = snackbar,
            queue = queue,
        )
        if (!background) {
            Box(modifier = Modifier.fillMaxSize().tvPlayerEdges()) {
                TvSnackbarHost(snackbar, Modifier.align(Alignment.BottomStart), raised = true)
            }
        }
    }
}

private const val SHELL_STATE_KEY = "tv-shell"

@Composable
private fun rememberPlaybackQueue(
    backgrounds: Boolean,
    mediaType: PlaybackMediaType,
    controller: PlaybackController,
    playerFactory: TvPlayerFactory,
    reporter: TvPlaybackReporter,
    findUpNext: PlaybackFindNeighbours?,
    onSessionRejected: suspend () -> Unit,
    analytics: PlaybackAnalyticsReporter,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    onStopRequested: () -> Unit,
): TvPlaybackQueue? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnSessionRejected by rememberUpdatedState(onSessionRejected)
    val currentOnPlayerFailure by rememberUpdatedState(onPlayerFailure)
    val currentOnStopRequested by rememberUpdatedState(onStopRequested)
    val hosted = TvPlaybackService.audioHost.queueFor(controller)
    val local = remember(backgrounds, mediaType, controller, playerFactory, reporter, findUpNext, analytics, hosted) {
        if (!backgrounds || mediaType != PlaybackMediaType.AUDIO || hosted != null) return@remember null
        TvPlaybackQueue(
            context = context,
            factory = playerFactory,
            reporter = reporter,
            controller = controller,
            folder = TvFolderLookup(controller.state, findUpNext) { currentOnSessionRejected() },
            defaultAnalytics = analytics,
            onFailure = { failure, positionMillis -> currentOnPlayerFailure(failure, positionMillis) },
            onStopRequested = { currentOnStopRequested() },
        )
    }
    DisposableEffect(local) {
        local?.start()
        onDispose { local?.close() }
    }
    val queue = hosted ?: local
    DisposableEffect(queue, lifecycle, analytics) {
        val binding = queue?.bindUi(
            lifecycle = lifecycle,
            analytics = analytics,
            onFailure = { failure, positionMillis -> currentOnPlayerFailure(failure, positionMillis) },
            onExit = { currentOnStopRequested() },
        )
        onDispose { binding?.close() }
    }
    return queue
}

@Composable
private fun rememberNeighbours(
    state: PlaybackState,
    queue: TvPlaybackQueue?,
    findUpNext: PlaybackFindNeighbours?,
    onSessionRejected: suspend () -> Unit,
): PlaybackNeighbours? {
    val folder = rememberFolderNeighbours(state, findUpNext.takeIf { queue == null }, onSessionRejected)
    val queued = (queue?.neighbours ?: NO_QUEUE).collectAsState()
    return if (queue == null) folder else queued.value
}

private val NO_QUEUE = MutableStateFlow<PlaybackNeighbours?>(null)

private fun TvPlaybackQueue?.activeReporter(fallback: TvPlaybackReporter): TvPlaybackReporter =
    this?.reporter ?: fallback

private fun PlaybackController.showsPlayerFailure(failure: PlaybackFailure, positionMillis: Long): Boolean =
    dispatch(PlaybackEvent.PlayerFailed(failure, positionMillis)) && state.value.content is PlaybackContent.Failed
