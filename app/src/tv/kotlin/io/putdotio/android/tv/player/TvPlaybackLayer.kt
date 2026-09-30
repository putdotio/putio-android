package io.putdotio.android.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.SubtitleStartupPolicy

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
        player()
    } else {
        saveableState.SaveableStateProvider(SHELL_STATE_KEY, shell)
    }
}

/**
 * One session playback: the shared controller's state on the TV player screen. With
 * [autoplayNextVideo] a finished video moves on to the next one in its folder, by the rules
 * mobile uses, and playback leaves after the folder's last.
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
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val failure = when (val content = state.content) {
        is PlaybackContent.Failed -> content.failure
        is PlaybackContent.NextFailed -> content.failure
        else -> null
    }
    val sessionRejected = failure is PlaybackFailure.AuthenticationRequired
    LaunchedEffect(sessionRejected) { if (sessionRejected) onSessionRejected() }
    TvPlayerScreen(
        state = state,
        onBack = onExit,
        onRetry = { controller.dispatch(PlaybackEvent.Retry) },
        onResume = { controller.dispatch(PlaybackEvent.Resume) },
        onRestart = { controller.dispatch(PlaybackEvent.Restart) },
        onPlayerFailure = { failure, positionMillis ->
            controller.dispatch(PlaybackEvent.PlayerFailed(failure, positionMillis))
        },
        onRefreshConversion = { controller.dispatch(PlaybackEvent.RefreshConversion) },
        onStartConversion = { controller.dispatch(PlaybackEvent.StartConversion) },
        playerFactory = playerFactory,
        reporter = reporter,
        subtitleStartupPolicy = subtitleStartupPolicy,
        autoplayNextVideo = autoplayNextVideo,
        onPlaybackEnded = { controller.dispatch(PlaybackEvent.PlayerEnded) },
    )
}

private const val SHELL_STATE_KEY = "tv-shell"
