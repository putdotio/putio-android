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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import io.putdotio.android.playback.withReportingLease
import io.putdotio.android.tv.PANE_INSET
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.android.tv.tvOverscanPadding
import io.putdotio.sdk.files.PlaybackSource
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import io.putdotio.android.playback.AudioSelection
import io.putdotio.android.playback.SubtitleCueOverlay
import io.putdotio.android.playback.SubtitleSelection
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.displayAspectRatioOrNull
import io.putdotio.android.playback.orHiddenWhen
import io.putdotio.android.playback.playbackAudioTracks
import io.putdotio.android.playback.playbackSubtitleTracks
import io.putdotio.android.playback.restoreSubtitleSelection
import io.putdotio.android.playback.toPlaybackMillis
import io.putdotio.android.playback.withAudioSelection
import io.putdotio.android.playback.withAudioTrack
import io.putdotio.android.playback.withRetainedAudioSelection
import io.putdotio.android.playback.withSubtitleSelection
import io.putdotio.android.playback.withSubtitleTracks
import io.putdotio.android.tv.TvChoice
import io.putdotio.android.tv.TvChoiceDialog
import io.putdotio.sdk.files.PlaybackSourceKind
import java.util.UUID
import kotlinx.coroutines.delay

internal const val TV_PLAYER_TAG = "tv-player"
internal const val TV_PLAYER_CONTROLS_TAG = "tv-player-controls"
internal const val TV_PLAYER_SEEK_BAR_TAG = "tv-player-seek-bar"
internal const val TV_PLAYER_ELAPSED_TAG = "tv-player-elapsed"
internal const val TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private const val TV_PLAYER_POSITION_POLL_MILLIS = 500L
private const val TV_PLAYER_SCRIM_ALPHA = 0.75f
private val PROCESS_KEY = UUID.randomUUID().toString()

/**
 * Full-screen TV playback of one Files item: a saved position first offers Continue or Start
 * from the beginning, then the source plays, Center or the remote's play/pause key toggles
 * playback, any key reveals the title, option buttons and seek bar for three seconds (they
 * stay while paused), Left, Right, rewind and fast-forward scrub, Up reaches the Language,
 * Subtitles and Speed pickers, and Back dismisses a picker, seek mode, then the controls,
 * before it leaves playback. Subtitles start per [subtitleStartupPolicy] until the viewer
 * picks. Positions are written back through [reporter]. A finished video leaves playback, or
 * with [autoplayNextVideo] asks [onPlaybackEnded] for the next one, leaving when it declines.
 */
@Composable
internal fun TvPlayerScreen(
    state: PlaybackState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    modifier: Modifier = Modifier,
    onRefreshConversion: () -> Unit = {},
    onStartConversion: () -> Unit = {},
    playerFactory: TvPlayerFactory = DefaultTvPlayerFactory,
    reporter: TvPlaybackReporter = TvPlaybackReporter.None,
    subtitleStartupPolicy: SubtitleStartupPolicy? = null,
    autoplayNextVideo: Boolean = false,
    onPlaybackEnded: () -> Boolean = { false },
) {
    // Ready playback registers its own Back for the overlay stack.
    BackHandler(enabled = state.content !is PlaybackContent.Ready, onBack = onBack)
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (val content = state.content) {
            is PlaybackContent.Ready ->
                TvReadyPlayer(
                    source = content.source,
                    useStartFrom = content.useStartFrom,
                    target = state.target,
                    resumePositionMillis = state.resumePositionMillis,
                    subtitleStartupPolicy = subtitleStartupPolicy.orHiddenWhen(content.subtitlesHidden),
                    playerFactory = playerFactory,
                    reporter = reporter,
                    onPlayerFailure = onPlayerFailure,
                    onExit = onBack,
                    autoplayNextVideo = autoplayNextVideo,
                    onPlaybackEnded = onPlaybackEnded,
                )

            is PlaybackContent.AwaitingResume -> {
                val durationSeconds = state.target.durationSeconds
                if (durationSeconds != null && durationSeconds > 0.0 && content.source.startFromSeconds > 0.0) {
                    TvResumePlaybackDialog(
                        title = state.target.name,
                        startFromSeconds = content.source.startFromSeconds,
                        durationSeconds = durationSeconds,
                        onResume = onResume,
                        onRestart = onRestart,
                        onDismiss = onBack,
                    )
                } else {
                    // Without a duration there is nothing to preview; like the RN player, continue.
                    LaunchedEffect(content) { onResume() }
                    TvStatusScreen(stringResource(R.string.tv_player_loading))
                }
            }

            is PlaybackContent.Loading,
            is PlaybackContent.FindingNext,
            PlaybackContent.Session,
            -> TvStatusScreen(stringResource(R.string.tv_player_loading))

            is PlaybackContent.Conversion ->
                TvConversionScreen(
                    title = state.target.name,
                    conversion = content,
                    onRefresh = onRefreshConversion,
                    onStartConversion = onStartConversion,
                )

            is PlaybackContent.Unsupported -> TvStatusScreen(stringResource(R.string.tv_player_unsupported_title))

            is PlaybackContent.Failed -> TvPlaybackFailureScreen(content.failure, onRetry)

            is PlaybackContent.NextFailed -> TvPlaybackFailureScreen(content.failure, onRetry)

            PlaybackContent.Ended -> LaunchedEffect(Unit) { onBack() }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun TvReadyPlayer(
    source: PlaybackSource,
    useStartFrom: Boolean,
    target: PlaybackTarget,
    resumePositionMillis: Long?,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    playerFactory: TvPlayerFactory,
    reporter: TvPlaybackReporter,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    onExit: () -> Unit,
    autoplayNextVideo: Boolean,
    onPlaybackEnded: () -> Boolean,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Recreating the activity (a remote or keyboard connecting, a locale change) rebuilds the
    // player; the new one continues, paused, from where the old one was stopped. The key is
    // scoped to this process and file so a position saved before process death never applies.
    var stoppedAtMillis by rememberSaveable(source, key = "tv-player-stopped-at-$PROCESS_KEY-${target.fileId.value}") {
        mutableStateOf<Long?>(null)
    }
    var options by rememberSaveable(
        source,
        stateSaver = TvPlaybackOptionsSaver,
        key = "tv-player-options-${target.fileId.value}",
    ) { mutableStateOf(TvPlaybackOptions()) }
    val player = remember(source, playerFactory) { playerFactory.create(context, target.mediaType) }
    // The player's own text defaults, before any choice narrows them; Automatic returns to these.
    val defaultTrackSelection = remember(player) { player.trackSelectionParameters }
    val prepared = remember(player) {
        source.preparePlayback(target.name, target.mediaType, stoppedAtMillis ?: resumePositionMillis)
    }
    val currentOnPlayerFailure by rememberUpdatedState(onPlayerFailure)
    val currentOnExit by rememberUpdatedState(onExit)
    val currentAutoplayNextVideo by rememberUpdatedState(autoplayNextVideo)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
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
                    TvPlayerCommand.PlayNext -> if (!currentOnPlaybackEnded()) currentOnExit()
                }
            }
        }
    }
    var playWhenReady by remember(player) { mutableStateOf(stoppedAtMillis == null) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var parameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    var cues by remember(player) { mutableStateOf(emptyList<Cue>()) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    val currentSubtitlePolicy by rememberUpdatedState(subtitleStartupPolicy)
    DisposableEffect(player) {
        fun keepChoices(current: Tracks) {
            // A picked subtitle track is found again in each new track list, and automatic
            // subtitles find the account's default; Off stays off because the text type stays
            // disabled whatever the tracks do (#45).
            val withSubtitles = player.trackSelectionParameters.withSubtitleTracks(
                retained = options.subtitles,
                startupPolicy = currentSubtitlePolicy,
                tracks = current.playbackSubtitleTracks(),
                textDefaults = defaultTrackSelection,
            )
            val withAudio = withSubtitles.withRetainedAudioSelection(options.audio, current.playbackAudioTracks())
            if (withAudio != player.trackSelectionParameters) player.trackSelectionParameters = withAudio
        }
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                playWhenReady = value
                apply(overlay.playingChanged(value))
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // The overlay's own commit drops its scrub before seeking, so this is anyone else's.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) apply(overlay.soughtElsewhere())
            }

            override fun onPlaybackStateChanged(value: Int) {
                playbackState = value
                if (value == Player.STATE_ENDED) apply(overlay.ended(autoplayNext = currentAutoplayNextVideo))
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnPlayerFailure(error.toPlaybackFailure(), player.currentPosition.coerceAtLeast(0L))
            }

            override fun onTracksChanged(value: Tracks) {
                tracks = value
                keepChoices(value)
            }

            override fun onTrackSelectionParametersChanged(value: TrackSelectionParameters) {
                parameters = value
            }

            override fun onPlaybackParametersChanged(value: PlaybackParameters) {
                speed = value.speed
                // The media session sets speed on the player directly; keep it like a picked one.
                if (options.speed != value.speed) options = options.copy(speed = value.speed)
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onVideoSizeChanged(value: VideoSize) {
                videoSize = value
            }
        }
        player.addListener(listener)
        player.trackSelectionParameters =
            restoreSubtitleSelection(
                defaults = defaultTrackSelection,
                retained = options.subtitles,
                startupPolicy = subtitleStartupPolicy,
            ).withAudioSelection(options.audio, emptyList())
        player.setPlaybackSpeed(options.speed)
        // A source resolved with the resume setting off carries no lease and writes nothing.
        val lease = if (useStartFrom) reporter.lease(target.fileId.value) else null
        val item = lease?.let(prepared.mediaItem::withReportingLease) ?: prepared.mediaItem
        player.setMediaItem(item, prepared.startPositionMillis)
        player.playWhenReady = stoppedAtMillis == null
        player.prepare()
        val positions = reporter.observe(player)
        // The system's play and pause go through the overlay: during a scrub the player is already
        // paused, so a pause there changes nothing the listener would hear. The session stays
        // published while the screen is stopped, where a play would run hidden playback.
        val session = playerFactory.publish(
            context,
            tvSessionPlayer(player) { play ->
                if (!play || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    apply(overlay.sessionPlaying(play))
                }
            },
        )
        onDispose {
            // Captures the exit position while the player still has it.
            positions.close()
            session.close()
            player.removeListener(listener)
            player.release()
        }
    }
    // Account settings that arrive after playback started still decide until the viewer picks;
    // hiding overrides a pick too (#237).
    LaunchedEffect(player, subtitleStartupPolicy, options.subtitles == null) {
        val policy = subtitleStartupPolicy ?: return@LaunchedEffect
        if (options.subtitles != null && policy.showSubtitles) return@LaunchedEffect
        val current = player.trackSelectionParameters
        val updated = if (policy.showSubtitles && policy.autoSelectSubtitles) {
            current.withSubtitleSelection(
                SubtitleSelection.Automatic,
                player.currentTracks.playbackSubtitleTracks(),
                defaultTrackSelection,
            )
        } else {
            restoreSubtitleSelection(current, null, policy)
        }
        if (updated != current) player.trackSelectionParameters = updated
    }
    // Back walks the overlay stack before it leaves; see TvPlayerOverlay.back.
    BackHandler { apply(overlay.back()) }
    // Home or another app takes the screen: stop where we are and show the paused controls.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        apply(overlay.setPlaying(play = false))
        apply(overlay.reveal())
    }
    // ON_PAUSE precedes saving instance state on every API level; ON_STOP follows it before API 28.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { stoppedAtMillis = player.currentPosition.coerceAtLeast(0L) }
    val view = LocalView.current
    val keepScreenOn = playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val audioTracks = tracks.playbackAudioTracks()
    val subtitleTracks = tracks.playbackSubtitleTracks()
    // hide_subtitles hides subtitles entirely, as every reference player does (#237).
    val subtitlesHidden = subtitleStartupPolicy?.showSubtitles == false
    val buttons = tvOptionButtons(audioTracks.size, if (subtitlesHidden) 0 else subtitleTracks.size)
    // A Subtitles picker opened before the settings arrived closes once they hide subtitles.
    LaunchedEffect(subtitlesHidden, overlay.picker) {
        if (subtitlesHidden && overlay.picker == TvPlayerControl.Subtitles) apply(overlay.closePicker())
    }
    // Off is authoritative: nothing is drawn while the text type is disabled, whatever cues the
    // renderer last delivered (#45: subtitles that stayed on screen after being turned off).
    val subtitlesOn = C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes
    val shownSubtitle = if (subtitlesOn) subtitleTracks.indexOfFirst { it.selected } else -1

    // Playing controls hide after three seconds without a key; paused, scrubbing or picking ones stay.
    LaunchedEffect(overlay.controlsVisible, overlay.activity, playWhenReady, overlay.scrub == null, overlay.picker) {
        if (overlay.controlsVisible && playWhenReady && overlay.scrub == null && overlay.picker == null) {
            delay(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS)
            apply(overlay.hideTimedOut())
        }
    }
    val focus = remember { FocusRequester() }
    // Also after a picker closes, so its dialog window hands the keys back to the player.
    LaunchedEffect(focus, overlay.picker == null) { if (overlay.picker == null) focus.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TV_PLAYER_TAG)
            .focusRequester(focus)
            .onKeyEvent { event ->
                // Back belongs to the BackHandler; everything else is the player's.
                if (event.key == Key.Back || event.key !in TV_PLAYER_KEYS) return@onKeyEvent false
                val focused = if (overlay.controlsVisible) overlay.focusIn(buttons) else TvPlayerControl.SeekBar
                if (event.type == KeyEventType.KeyDown) {
                    val direction = event.key.scrubDirection(onSeekBar = focused == TvPlayerControl.SeekBar)
                    val move = event.key.focusMove()
                    apply(
                        when {
                            direction != null && player.canScrub() ->
                                overlay.scrub(
                                    direction = direction,
                                    positionMillis = player.currentPosition.coerceAtLeast(0L),
                                    durationMillis = player.duration,
                                    playing = player.playWhenReady,
                                    nowMillis = event.nativeKeyEvent.eventTime,
                                    repeat = event.nativeKeyEvent.repeatCount > 0,
                                )
                            move != null -> overlay.moveFocus(move, buttons)
                            else -> overlay.reveal()
                        },
                    )
                } else if (event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                            apply(
                                if (focused == TvPlayerControl.SeekBar) {
                                    overlay.select(player.playWhenReady)
                                } else {
                                    overlay.openPicker(focused)
                                },
                            )
                        Key.MediaPlayPause -> apply(overlay.select(player.playWhenReady))
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
            if (subtitlesOn) {
                SubtitleCueOverlay(
                    cues = cues,
                    videoAspectRatio = videoSize.displayAspectRatioOrNull(),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        if (overlay.controlsVisible) {
            TvPlayerControls(
                player = player,
                title = target.name,
                paused = !playWhenReady,
                scrubTargetMillis = overlay.scrub?.targetMillis,
                startPositionMillis = prepared.startPositionMillis,
                listingDurationMillis = target.durationSeconds?.toPlaybackMillis(),
                buttons = buttons,
                focused = overlay.focusIn(buttons),
                subtitlesShown = shownSubtitle >= 0,
                speed = speed,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    val closePicker = { apply(overlay.closePicker()) }
    val dismissPicker = { apply(overlay.back()) }
    val locale = LocalConfiguration.current.locales[0]
    when (overlay.picker) {
        TvPlayerControl.Language -> {
            val labels = pickerLabels(
                tvTrackLabels(
                    tracks = audioTracks.map { it.group.getFormat(it.trackIndex).let { format -> TvTrackName(format.label, format.language) } },
                    languageFirst = false,
                    locale = locale,
                    numbered = audioTracks.indices.map { stringResource(R.string.tv_player_audio_track_number, it + 1) },
                ),
            )
            TvChoiceDialog(
                title = stringResource(R.string.tv_player_audio_tracks),
                choices = audioTracks.indices.map { TvChoice(it, labels[it]) },
                selected = audioTracks.indexOfFirst { it.selected }.takeIf { it >= 0 },
                onSelect = { index ->
                    val track = audioTracks[index]
                    options = options.copy(audio = AudioSelection.Track(track.identity))
                    player.trackSelectionParameters = player.trackSelectionParameters.withAudioTrack(track)
                    closePicker()
                },
                onDismiss = dismissPicker,
            )
        }

        TvPlayerControl.Subtitles -> if (!subtitlesHidden) {
            val labels = pickerLabels(
                tvTrackLabels(
                    tracks = subtitleTracks.map { it.group.getFormat(it.trackIndex).let { format -> TvTrackName(format.label, format.language) } },
                    languageFirst = source.kind == PlaybackSourceKind.MP4,
                    locale = locale,
                    numbered = subtitleTracks.indices.map { stringResource(R.string.tv_player_subtitle_number, it + 1) },
                ),
            )
            TvChoiceDialog(
                title = stringResource(R.string.tv_player_subtitles),
                choices = listOf(TvChoice(SUBTITLES_OFF, stringResource(R.string.tv_player_subtitles_off))) +
                    subtitleTracks.indices.map { TvChoice(it, labels[it]) },
                selected = shownSubtitle.takeIf { it >= 0 } ?: SUBTITLES_OFF,
                onSelect = { index ->
                    val selection = subtitleTracks.getOrNull(index)
                        ?.let { SubtitleSelection.Track(it.identity) }
                        ?: SubtitleSelection.Off
                    options = options.copy(subtitles = selection)
                    player.trackSelectionParameters = player.trackSelectionParameters.withSubtitleSelection(
                        selection = selection,
                        tracks = subtitleTracks,
                        textDefaults = defaultTrackSelection,
                    )
                    closePicker()
                },
                onDismiss = dismissPicker,
            )
        }

        TvPlayerControl.Speed ->
            TvChoiceDialog(
                title = stringResource(R.string.tv_player_playback_speed),
                choices = TV_PLAYBACK_SPEEDS.map {
                    TvChoice(it, stringResource(R.string.tv_player_speed_value, tvSpeedValue(it)))
                },
                selected = speed,
                onSelect = { choice ->
                    options = options.copy(speed = choice)
                    player.setPlaybackSpeed(choice)
                    closePicker()
                },
                onDismiss = dismissPicker,
            )

        TvPlayerControl.SeekBar, null -> Unit
    }
}

@Composable
private fun pickerLabels(labels: List<String>): List<String> {
    val repeated = repeatedTrackLabels(labels)
    return labels.mapIndexed { index, label ->
        if (label in repeated) stringResource(R.string.tv_player_track_disambiguated, label, index + 1) else label
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

private const val SUBTITLES_OFF = -1

/**
 * Rewind and fast-forward scrub from anywhere, pulling focus back to the seek bar, as the RN
 * player's did; Left and Right scrub only on the seek bar and move between buttons otherwise.
 */
private fun Key.scrubDirection(onSeekBar: Boolean): TvScrubDirection? =
    when (this) {
        Key.MediaRewind -> TvScrubDirection.Backward
        Key.MediaFastForward -> TvScrubDirection.Forward
        Key.DirectionLeft -> TvScrubDirection.Backward.takeIf { onSeekBar }
        Key.DirectionRight -> TvScrubDirection.Forward.takeIf { onSeekBar }
        else -> null
    }

private fun Key.focusMove(): TvFocusMove? =
    when (this) {
        Key.DirectionUp -> TvFocusMove.Up
        Key.DirectionDown -> TvFocusMove.Down
        Key.DirectionLeft -> TvFocusMove.Left
        Key.DirectionRight -> TvFocusMove.Right
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
    startPositionMillis: Long,
    listingDurationMillis: Long?,
    buttons: List<TvPlayerControl>,
    focused: TvPlayerControl,
    subtitlesShown: Boolean,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    // Until the player has its item the bar starts where playback will, not at zero.
    var positionMillis by remember(player) {
        mutableLongStateOf(if (player.currentMediaItem == null) startPositionMillis else player.currentPosition.coerceAtLeast(0L))
    }
    var durationMillis by remember(player) { mutableLongStateOf(player.duration) }
    // The timeline and seeks update the bar at once; the poll covers steady playback.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) {
                    positionMillis = player.currentPosition.coerceAtLeast(0L)
                    durationMillis = player.duration
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Polls only while shown and playing; the controls hide three seconds into playback.
    LaunchedEffect(player, paused) {
        while (true) {
            if (player.currentMediaItem != null) positionMillis = player.currentPosition.coerceAtLeast(0L)
            durationMillis = player.duration
            if (paused) break
            delay(TV_PLAYER_POSITION_POLL_MILLIS)
        }
    }
    // A pending scrub shows its target until it is committed or dismissed.
    val shownMillis = scrubTargetMillis ?: positionMillis
    // A stream reports its duration once loaded; the listing's stands in until then.
    val knownDuration = durationMillis.takeIf { it != C.TIME_UNSET && it > 0L }
        ?: listingDurationMillis?.takeIf { it > 0L }
    val elapsed = shownMillis.elapsedLabel()
    val seekBarDescription = knownDuration?.let {
        stringResource(R.string.tv_player_seek_bar, elapsed, it.elapsedLabel())
    } ?: elapsed
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = TV_PLAYER_SCRIM_ALPHA))
            .tvOverscanPadding()
            .padding(PANE_INSET)
            .testTag(TV_PLAYER_CONTROLS_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(TITLE_WIDTH_FRACTION),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                buttons.forEach { button ->
                    TvPlayerOptionButton(
                        control = button,
                        focused = button == focused,
                        subtitlesShown = subtitlesShown,
                        speed = speed,
                    )
                }
            }
        }
        TvSeekBar(
            fraction = knownDuration?.let { (shownMillis.toFloat() / it).coerceIn(0f, 1f) } ?: 0f,
            description = seekBarDescription,
            scrubbing = scrubTargetMillis != null,
            focused = focused == TvPlayerControl.SeekBar,
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
 * One option button, as the RN player's `OptionsOverlayTriggerButton`: an icon in a pill that
 * fills when focused, with its name above it only while focused. Focus follows the overlay's
 * D-pad state rather than Compose focus, which stays on the player for the scrubbing keys.
 */
@Composable
private fun TvPlayerOptionButton(
    control: TvPlayerControl,
    focused: Boolean,
    subtitlesShown: Boolean,
    speed: Float,
) {
    val label = when (control) {
        TvPlayerControl.Language -> stringResource(R.string.tv_player_language)
        TvPlayerControl.Subtitles -> stringResource(R.string.tv_player_subtitles)
        else -> stringResource(R.string.tv_player_speed)
    }
    val icon = when (control) {
        TvPlayerControl.Language -> R.drawable.ic_ph_headphones
        TvPlayerControl.Subtitles -> if (subtitlesShown) R.drawable.ic_ph_subtitles else R.drawable.ic_ph_subtitles_slash
        else -> R.drawable.ic_ph_gauge
    }
    val state = when (control) {
        TvPlayerControl.Subtitles -> stringResource(
            if (subtitlesShown) R.string.tv_player_subtitles_on_state else R.string.tv_player_subtitles_off_state,
        )
        TvPlayerControl.Speed -> stringResource(R.string.tv_player_speed_value, tvSpeedValue(speed))
        else -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alpha(if (focused) 1f else 0f).clearAndSetSemantics {},
        )
        Box(
            modifier = Modifier
                .size(OPTION_BUTTON)
                .clip(CircleShape)
                .background(if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    selected = focused
                    state?.let { stateDescription = it }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(OPTION_ICON),
            )
        }
    }
}

/**
 * The played part in `primary` over `surfaceVariant`. The thumb at the playhead shows while the
 * seek bar has the overlay's focus, as the RN player's did; scrubbing enlarges it.
 */
@Composable
private fun TvSeekBar(fraction: Float, description: String, scrubbing: Boolean, focused: Boolean) {
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
        if (focused) Box(
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
private val OPTION_BUTTON = 40.dp
private val OPTION_ICON = 20.dp
