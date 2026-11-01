package io.putdotio.android.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.putdotio.android.PutioFailure
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.playerFailureTelemetry
import io.putdotio.android.playback.putioFailure
import io.putdotio.android.playback.rememberNextEpisode
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.tv.TvNoticeDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Playback replaces the signed-in shell rather than covering it, so no shell control can
 * take D-pad focus behind the video. The shell's saved state (destination, drawer) is kept
 * while it is away; on return the Files pane puts focus back on the row that was playing.
 */
@Composable
internal fun TvPlaybackLayer(
    playing: Boolean,
    player: @Composable () -> Unit,
    shell: @Composable () -> Unit,
) {
    val saveableState = rememberSaveableStateHolder()
    if (playing) {
        LaunchedEffect(Unit) { ProductAnalytics.capture(AnalyticsEvent.ScreenViewed(AnalyticsEvent.Screen.Player)) }
        player()
    } else {
        saveableState.SaveableStateProvider(SHELL_STATE_KEY, shell)
    }
}

/**
 * One session playback: the shared controller's state on the TV player screen. With
 * [autoplayNextVideo] a finished video moves on to the next one in its folder, by the rules
 * mobile uses, and playback leaves after the folder's last. With [findNextEpisode], the account's
 * next_episode, a playing video offers the next one as a chip.
 */
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
    subtitlesFirst: Boolean = false,
    onSubtitlesShown: () -> Unit = {},
    findUpNext: TvFindNeighbours? = null,
    openInVlc: (suspend (PlaybackTarget) -> Int?)? = null,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val nextEpisode = rememberNextEpisode(state, findNextEpisode, onSessionRejected)
    val upNext = rememberUpNext(state, findUpNext.takeIf { autoplayNextVideo }, onSessionRejected)
    val vlcScope = rememberCoroutineScope()
    var vlcNotice by remember { mutableStateOf<Int?>(null) }
    var vlcHandoff by remember { mutableStateOf<Job?>(null) }
    val failure = when (val content = state.content) {
        is PlaybackContent.Failed -> content.failure
        is PlaybackContent.NextFailed -> content.failure
        else -> null
    }
    val sessionRejected = failure?.putioFailure is PutioFailure.AuthenticationRequired
    LaunchedEffect(sessionRejected) { if (sessionRejected) onSessionRejected() }
    TvPlayerScreen(
        state = state,
        onBack = onExit,
        onRetry = { controller.dispatch(PlaybackEvent.Retry) },
        onResume = { controller.dispatch(PlaybackEvent.Resume) },
        onRestart = { controller.dispatch(PlaybackEvent.Restart) },
        onPlayerFailure = { failure, positionMillis ->
            val telemetry = controller.state.value.playerFailureTelemetry(failure)
            if (controller.dispatch(PlaybackEvent.PlayerFailed(failure, positionMillis))) {
                PutioDiagnostics.capture(telemetry)
            }
        },
        onRefreshConversion = { controller.dispatch(PlaybackEvent.RefreshConversion) },
        onStartConversion = { controller.dispatch(PlaybackEvent.StartConversion) },
        playerFactory = playerFactory,
        reporter = reporter,
        subtitleStartupPolicy = subtitleStartupPolicy,
        autoplayNextVideo = autoplayNextVideo,
        onPlaybackEnded = { controller.dispatch(PlaybackEvent.PlayerEnded) },
        nextEpisode = nextEpisode,
        onNextEpisode = { nextEpisode?.let { controller.dispatch(PlaybackEvent.NextChosen(it)) } },
        onUpNext = { controller.dispatch(PlaybackEvent.NextChosen(it)) },
        analytics = controller.analyticsReporter,
        subtitlesFirst = subtitlesFirst,
        onSubtitlesShown = onSubtitlesShown,
        upNext = upNext,
        onOpenInVlc = openInVlc?.let { open ->
            {
                if (vlcHandoff?.isActive != true) {
                    val target = state.target
                    vlcHandoff = vlcScope.launch { vlcNotice = open(target) }
                }
            }
        },
    )
    vlcNotice?.let { notice -> TvNoticeDialog(title = stringResource(notice), onDismiss = { vlcNotice = null }) }
}

private const val SHELL_STATE_KEY = "tv-shell"
