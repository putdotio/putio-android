package io.putdotio.android.tv.player

import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.playbackSurfaceType
import io.putdotio.android.playback.preparePlayback
import io.putdotio.android.playback.toPlaybackFailure
import io.putdotio.android.tv.OVERSCAN_X
import io.putdotio.android.tv.OVERSCAN_Y
import io.putdotio.android.tv.PANE_INSET
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.sdk.files.PlaybackSource
import java.util.UUID
import kotlinx.coroutines.delay

internal const val TV_PLAYER_TAG = "tv-player"
internal const val TV_PLAYER_CONTROLS_TAG = "tv-player-controls"
internal const val TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private const val TV_PLAYER_POSITION_POLL_MILLIS = 500L
private const val TV_PLAYER_SCRIM_ALPHA = 0.75f
private val PROCESS_KEY = UUID.randomUUID().toString()

/**
 * Full-screen TV playback of one Files item: the resolved source plays at once, Center or
 * the remote's play/pause key toggles playback, any key reveals the title and progress for
 * three seconds (they stay while paused), and Back leaves playback.
 */
@Composable
internal fun TvPlayerScreen(
    state: PlaybackState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    modifier: Modifier = Modifier,
    playerFactory: TvPlayerFactory = DefaultTvPlayerFactory,
) {
    BackHandler(onBack = onBack)
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (val content = state.content) {
            is PlaybackContent.Ready ->
                TvReadyPlayer(
                    source = content.source,
                    target = state.target,
                    resumePositionMillis = state.resumePositionMillis,
                    playerFactory = playerFactory,
                    onPlayerFailure = onPlayerFailure,
                    onEnded = onBack,
                )

            is PlaybackContent.AwaitingResume -> {
                // No resume prompt yet: continue from the saved position, the prompt's preferred choice.
                LaunchedEffect(content) { onResume() }
                TvStatusScreen(stringResource(R.string.tv_player_loading))
            }

            is PlaybackContent.Loading,
            is PlaybackContent.FindingNext,
            PlaybackContent.Session,
            -> TvStatusScreen(stringResource(R.string.tv_player_loading))

            is PlaybackContent.Conversion ->
                TvStatusScreen(
                    title = stringResource(R.string.tv_player_conversion_title),
                    message = stringResource(R.string.tv_player_conversion_message),
                    action = stringResource(R.string.tv_player_check_again),
                    onAction = onRetry,
                )

            is PlaybackContent.Unsupported -> TvStatusScreen(stringResource(R.string.tv_player_unsupported_title))

            is PlaybackContent.Failed,
            is PlaybackContent.NextFailed,
            ->
                TvStatusScreen(
                    title = stringResource(R.string.tv_player_error_title),
                    message = stringResource(R.string.tv_player_error_message),
                    action = stringResource(R.string.tv_player_retry),
                    onAction = onRetry,
                )

            PlaybackContent.Ended -> LaunchedEffect(Unit) { onBack() }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun TvReadyPlayer(
    source: PlaybackSource,
    target: PlaybackTarget,
    resumePositionMillis: Long?,
    playerFactory: TvPlayerFactory,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    // Recreating the activity (a remote or keyboard connecting, a locale change) rebuilds the
    // player; the new one continues, paused, from where the old one was stopped. The key is
    // scoped to this process and file so a position saved before process death never applies.
    var stoppedAtMillis by rememberSaveable(source, key = "tv-player-stopped-at-$PROCESS_KEY-${target.fileId.value}") {
        mutableStateOf<Long?>(null)
    }
    val player = remember(source, playerFactory) { playerFactory.create(context, target.mediaType) }
    val currentOnPlayerFailure by rememberUpdatedState(onPlayerFailure)
    val currentOnEnded by rememberUpdatedState(onEnded)
    var playWhenReady by remember(player) { mutableStateOf(stoppedAtMillis == null) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                playWhenReady = value
            }

            override fun onPlaybackStateChanged(value: Int) {
                playbackState = value
                if (value == Player.STATE_ENDED) currentOnEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnPlayerFailure(error.toPlaybackFailure(), player.currentPosition.coerceAtLeast(0L))
            }
        }
        player.addListener(listener)
        val prepared = source.preparePlayback(target.name, target.mediaType, stoppedAtMillis ?: resumePositionMillis)
        player.setMediaItem(prepared.mediaItem, prepared.startPositionMillis)
        player.playWhenReady = stoppedAtMillis == null
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Home or another app takes the screen: stop where we are and let the viewer resume.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    // ON_PAUSE precedes saving instance state on every API level; ON_STOP follows it before API 28.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { stoppedAtMillis = player.currentPosition.coerceAtLeast(0L) }
    val view = LocalView.current
    val keepScreenOn = playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    var controlsRevealed by remember(player) { mutableStateOf(true) }
    var controlsActivity by remember(player) { mutableIntStateOf(0) }
    LaunchedEffect(controlsRevealed, playWhenReady, controlsActivity) {
        if (controlsRevealed && playWhenReady) {
            delay(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS)
            controlsRevealed = false
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TV_PLAYER_TAG)
            .focusRequester(focus)
            .onKeyEvent { event ->
                // Back belongs to the screen's BackHandler; everything else is the player's.
                if (event.key == Key.Back || event.key !in TV_PLAYER_KEYS) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    controlsRevealed = true
                    controlsActivity += 1
                } else if (event.type == KeyEventType.KeyUp) {
                    when (event.key.playbackCommand(player.playWhenReady)) {
                        true -> player.play()
                        false -> player.pause()
                        null -> Unit
                    }
                }
                true
            }
            .focusable(),
    ) {
        if (target.mediaType == PlaybackMediaType.VIDEO) {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = playbackSurfaceType(Build.VERSION.SDK_INT, Build.HARDWARE),
            )
        }
        if (controlsRevealed || !playWhenReady) {
            TvPlayerControls(
                player = player,
                title = target.name,
                paused = !playWhenReady,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Keys the player handles itself: the D-pad (so focus stays put) and play/pause. */
private val TV_PLAYER_KEYS = setOf(
    Key.DirectionCenter,
    Key.Enter,
    Key.NumPadEnter,
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
    Key.MediaPlayPause,
    Key.MediaPlay,
    Key.MediaPause,
)

/** True to play, false to pause, null for a key that only reveals the controls. */
private fun Key.playbackCommand(playWhenReady: Boolean): Boolean? =
    when (this) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> !playWhenReady
        Key.MediaPlay -> true
        Key.MediaPause -> false
        else -> null
    }

@Composable
private fun TvPlayerControls(
    player: Player,
    title: String,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    var positionMillis by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var durationMillis by remember(player) { mutableLongStateOf(player.duration) }
    // Polls only while shown and playing; the controls hide three seconds into playback.
    LaunchedEffect(player, paused) {
        while (true) {
            positionMillis = player.currentPosition.coerceAtLeast(0L)
            durationMillis = player.duration
            if (paused) break
            delay(TV_PLAYER_POSITION_POLL_MILLIS)
        }
    }
    val knownDuration = durationMillis.takeIf { it != C.TIME_UNSET && it > 0L }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = TV_PLAYER_SCRIM_ALPHA))
            .padding(horizontal = OVERSCAN_X + PANE_INSET, vertical = OVERSCAN_Y + PANE_INSET)
            .testTag(TV_PLAYER_CONTROLS_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(TITLE_WIDTH_FRACTION),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clearAndSetSemantics {},
        ) {
            val fraction = knownDuration?.let { (positionMillis.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = positionMillis.elapsedLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    painter = painterResource(if (paused) R.drawable.ic_ph_play_fill else R.drawable.ic_ph_pause_fill),
                    contentDescription = stringResource(
                        if (paused) R.string.tv_player_paused else R.string.tv_player_playing,
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (knownDuration != null) {
                Text(
                    text = knownDuration.elapsedLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun Long.elapsedLabel(): String = DateUtils.formatElapsedTime(this / MILLIS_PER_SECOND)

private const val MILLIS_PER_SECOND = 1_000L
private const val TITLE_WIDTH_FRACTION = 0.8f
