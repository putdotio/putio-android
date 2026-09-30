package io.putdotio.android.tv.player

import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
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
import kotlinx.coroutines.delay

internal const val TV_PLAYER_TAG = "tv-player"
internal const val TV_PLAYER_CONTROLS_TAG = "tv-player-controls"
internal const val TV_PLAYER_SEEK_BAR_TAG = "tv-player-seek-bar"
internal const val TV_PLAYER_ELAPSED_TAG = "tv-player-elapsed"
internal const val TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private const val TV_PLAYER_POSITION_POLL_MILLIS = 500L
private const val TV_PLAYER_SCRIM_ALPHA = 0.75f

/**
 * Full-screen TV playback of one Files item: the resolved source plays at once, Center or
 * the remote's play/pause key toggles playback, any key reveals the title and seek bar for
 * three seconds (they stay while paused), Left, Right, rewind and fast-forward scrub, and
 * Back dismisses seek mode, then the controls, before it leaves playback.
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
    // Ready playback registers its own Back for the overlay stack.
    BackHandler(enabled = state.content !is PlaybackContent.Ready, onBack = onBack)
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (val content = state.content) {
            is PlaybackContent.Ready ->
                TvReadyPlayer(
                    source = content.source,
                    target = state.target,
                    resumePositionMillis = state.resumePositionMillis,
                    playerFactory = playerFactory,
                    onPlayerFailure = onPlayerFailure,
                    onExit = onBack,
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
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(source, playerFactory) { playerFactory.create(context, target.mediaType) }
    val currentOnPlayerFailure by rememberUpdatedState(onPlayerFailure)
    val currentOnExit by rememberUpdatedState(onExit)
    var overlay by remember(player) { mutableStateOf(TvPlayerOverlay()) }
    val apply: (TvPlayerTransition) -> Unit = remember(player) {
        { transition ->
            overlay = transition.overlay
            transition.commands.forEach { command ->
                when (command) {
                    TvPlayerCommand.Play -> player.play()
                    TvPlayerCommand.Pause -> player.pause()
                    is TvPlayerCommand.SeekTo -> player.seekTo(command.positionMillis)
                    TvPlayerCommand.Exit -> currentOnExit()
                }
            }
        }
    }
    var playWhenReady by remember(player) { mutableStateOf(true) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                playWhenReady = value
            }

            override fun onPlaybackStateChanged(value: Int) {
                playbackState = value
                if (value == Player.STATE_ENDED) apply(overlay.ended())
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnPlayerFailure(error.toPlaybackFailure(), player.currentPosition.coerceAtLeast(0L))
            }
        }
        player.addListener(listener)
        val prepared = source.preparePlayback(target.name, target.mediaType, resumePositionMillis)
        player.setMediaItem(prepared.mediaItem, prepared.startPositionMillis)
        player.playWhenReady = true
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Back walks the overlay stack before it leaves; see TvPlayerOverlay.back.
    BackHandler { apply(overlay.back()) }
    // Home or another app takes the screen: stop where we are and show the paused controls.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        apply(overlay.setPlaying(play = false))
        apply(overlay.reveal())
    }
    val view = LocalView.current
    val keepScreenOn = playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Playing controls hide after three seconds without a key; paused or scrubbing ones stay.
    LaunchedEffect(overlay.controlsVisible, overlay.activity, playWhenReady, overlay.scrub == null) {
        if (overlay.controlsVisible && playWhenReady && overlay.scrub == null) {
            delay(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS)
            apply(overlay.hideTimedOut())
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
                // Back belongs to the BackHandler; everything else is the player's.
                if (event.key == Key.Back || event.key !in TV_PLAYER_KEYS) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val direction = event.key.scrubDirection()
                    apply(
                        if (direction != null && player.canScrub()) {
                            overlay.scrub(
                                direction = direction,
                                positionMillis = player.currentPosition.coerceAtLeast(0L),
                                durationMillis = player.duration,
                                playing = player.playWhenReady,
                                nowMillis = event.nativeKeyEvent.eventTime,
                                repeat = event.nativeKeyEvent.repeatCount > 0,
                            )
                        } else {
                            overlay.reveal()
                        },
                    )
                } else if (event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause ->
                            apply(overlay.select(player.playWhenReady))
                        Key.MediaPlay -> apply(overlay.setPlaying(play = true))
                        Key.MediaPause -> apply(overlay.setPlaying(play = false))
                        else -> Unit
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
        if (overlay.controlsVisible) {
            TvPlayerControls(
                player = player,
                title = target.name,
                paused = !playWhenReady,
                scrubTargetMillis = overlay.scrub?.targetMillis,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Keys the player handles itself: the D-pad (so focus stays put), play/pause and scrubbing. */
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
    Key.MediaFastForward,
    Key.MediaRewind,
)

/**
 * Left, Right, rewind and fast-forward scrub. The seek bar is the overlay's only focus target
 * until the track and speed buttons land, so rewind and fast-forward need no focus capture yet.
 */
private fun Key.scrubDirection(): TvScrubDirection? =
    when (this) {
        Key.DirectionLeft, Key.MediaRewind -> TvScrubDirection.Backward
        Key.DirectionRight, Key.MediaFastForward -> TvScrubDirection.Forward
        else -> null
    }

private fun Player.canScrub(): Boolean =
    isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
        isCurrentMediaItemSeekable &&
        duration != C.TIME_UNSET &&
        duration > 0L

@Composable
private fun TvPlayerControls(
    player: Player,
    title: String,
    paused: Boolean,
    scrubTargetMillis: Long?,
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
    // A pending scrub shows its target until it is committed or dismissed.
    val shownMillis = scrubTargetMillis ?: positionMillis
    val knownDuration = durationMillis.takeIf { it != C.TIME_UNSET && it > 0L }
    val elapsed = shownMillis.elapsedLabel()
    val seekBarDescription = knownDuration?.let {
        stringResource(R.string.tv_player_seek_bar, elapsed, it.elapsedLabel())
    } ?: elapsed
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
        TvSeekBar(
            fraction = knownDuration?.let { (shownMillis.toFloat() / it).coerceIn(0f, 1f) } ?: 0f,
            description = seekBarDescription,
            scrubbing = scrubTargetMillis != null,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = elapsed,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(TV_PLAYER_ELAPSED_TAG),
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

/**
 * The played part in `primary` over `surfaceVariant`, with a thumb at the playhead: the seek
 * bar holds the overlay's focus, as the RN player's did. Scrubbing enlarges the thumb.
 */
@Composable
private fun TvSeekBar(fraction: Float, description: String, scrubbing: Boolean) {
    val thumb = if (scrubbing) SEEK_THUMB_SCRUBBING else SEEK_THUMB
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEEK_THUMB_SCRUBBING)
            .testTag(TV_PLAYER_SEEK_BAR_TAG)
            .clearAndSetSemantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SEEK_TRACK)
                .clip(RoundedCornerShape(SEEK_TRACK / 2))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(SEEK_TRACK)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Box(
            modifier = Modifier
                .offset(x = (maxWidth * fraction - thumb / 2).coerceIn(0.dp, maxWidth - thumb))
                .size(thumb)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .border(SEEK_THUMB_BORDER, Color.White.copy(alpha = SEEK_THUMB_BORDER_ALPHA), CircleShape),
        )
    }
}

private fun Long.elapsedLabel(): String = DateUtils.formatElapsedTime(this / MILLIS_PER_SECOND)

private const val MILLIS_PER_SECOND = 1_000L
private const val TITLE_WIDTH_FRACTION = 0.8f
private const val SEEK_THUMB_BORDER_ALPHA = 0.25f
private val SEEK_TRACK = 8.dp
private val SEEK_THUMB = 20.dp
private val SEEK_THUMB_SCRUBBING = 28.dp
private val SEEK_THUMB_BORDER = 3.dp
