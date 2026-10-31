package io.putdotio.android.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import io.putdotio.android.R
import io.putdotio.android.playback.AudioSelection
import io.putdotio.android.playback.DelayedSubtitleCues
import io.putdotio.android.playback.PlaybackAnalyticsReporter
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleCueOverlay
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.displayAspectRatioOrNull
import io.putdotio.android.playback.orHiddenWhen
import io.putdotio.android.playback.playbackSurfaceType
import io.putdotio.android.playback.preparePlayback
import io.putdotio.android.playback.rememberSubtitleAppearanceStore
import io.putdotio.android.playback.restoreSubtitleSelection
import io.putdotio.android.playback.setSubtitleLead
import io.putdotio.android.playback.sidecarTracks
import io.putdotio.android.playback.toPlaybackFailure
import io.putdotio.android.playback.toPlaybackMillis
import io.putdotio.android.playback.withAudioSelection
import io.putdotio.android.playback.withAudioTrack
import io.putdotio.android.playback.withReportingLease
import io.putdotio.android.playback.withSubtitleSelection
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.sdk.files.PlaybackSource
import java.util.UUID
import kotlinx.coroutines.delay

internal const val TV_PLAYER_TAG = "tv-player"
internal const val TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private val PROCESS_KEY = UUID.randomUUID().toString()

/**
 * Full-screen TV playback of one Files item: a saved position first offers Resume or Start over,
 * then the source plays, Center or the remote's play/pause key toggles playback, any key reveals
 * the phone's controls for three seconds (they stay while paused), Left, Right, rewind and
 * fast-forward scrub, Down reaches the Audio, Subtitles and Speed chips and their side sheets,
 * and Back dismisses a sheet, seek mode, then the controls, before it leaves playback. Subtitles
 * start per [subtitleStartupPolicy] until the viewer picks. Positions are written back through
 * [reporter]. A finished video leaves playback, or with [autoplayNextVideo] asks
 * [onPlaybackEnded] for the next one, leaving when it declines; Next episode asks the same.
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
    /** The file's end, whatever autoplay does next. */
    analytics: PlaybackAnalyticsReporter = PlaybackAnalyticsReporter.None,
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
                    analytics = analytics,
                )

            is PlaybackContent.AwaitingResume -> {
                if (content.source.startFromSeconds > 0.0) {
                    TvResumePlaybackDialog(
                        title = state.target.name,
                        startFromSeconds = content.source.startFromSeconds,
                        onResume = onResume,
                        onRestart = onRestart,
                        onDismiss = onBack,
                    )
                } else {
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
    analytics: PlaybackAnalyticsReporter,
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
    val currentAnalytics by rememberUpdatedState(analytics)
    var overlay by remember(player) { mutableStateOf(TvPlayerOverlay()) }
    val apply: (TvPlayerTransition) -> Unit = remember(player) {
        { transition ->
            overlay = transition.overlay
            transition.commands.forEach { command ->
                player.perform(command, exit = { currentOnExit() }, playNext = { currentOnPlaybackEnded() })
            }
        }
    }
    var playWhenReady by remember(player) { mutableStateOf(stoppedAtMillis == null) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var parameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    var cues by remember(player) { mutableStateOf(emptyList<Cue>()) }
    val cueHistory = remember(player) { DelayedSubtitleCues() }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    val currentSubtitlePolicy by rememberUpdatedState(subtitleStartupPolicy)
    DisposableEffect(player) {
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
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    cueHistory.clear()
                    apply(overlay.soughtElsewhere())
                }
            }

            override fun onPlaybackStateChanged(value: Int) {
                playbackState = value
                if (value == Player.STATE_ENDED) {
                    currentAnalytics.reachedEnd()
                    apply(overlay.ended(autoplayNext = currentAutoplayNextVideo))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnPlayerFailure(error.toPlaybackFailure(), player.currentPosition.coerceAtLeast(0L))
            }

            override fun onTracksChanged(value: Tracks) {
                tracks = value
                player.keepChoices(value, options, currentSubtitlePolicy, defaultTrackSelection)
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
                cueHistory.record(player.currentPosition, cueGroup.cues)
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
        player.setMediaItem(
            prepared.mediaItem.leasedBy(reporter, target.fileId.value, useStartFrom),
            prepared.startPositionMillis,
        )
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
    // hiding overrides a pick too.
    LaunchedEffect(player, subtitleStartupPolicy, options.subtitles == null) {
        player.followSubtitlePolicy(subtitleStartupPolicy, options.subtitles, defaultTrackSelection)
    }
    BackHandler { apply(overlay.back()) }
    // Home or another app takes the screen: stop where we are and show the paused controls.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        apply(overlay.setPlaying(play = false))
        apply(overlay.reveal())
    }
    // ON_PAUSE precedes saving instance state on every API level; ON_STOP follows it before API 28.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { stoppedAtMillis = player.currentPosition.coerceAtLeast(0L) }
    val view = LocalView.current
    val keepScreenOn = keepsScreenOn(playWhenReady, playbackState)
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val choices = TvTrackChoices(tracks, parameters, subtitleStartupPolicy)
    val subtitlesHidden = choices.subtitlesHidden
    val chips = choices.chips(nextEpisode = autoplayNextVideo)
    // A Subtitles sheet opened before the settings arrived closes once they hide subtitles.
    LaunchedEffect(subtitlesHidden, overlay.sheet) {
        val subtitleSheet = overlay.sheet == TvPlayerSheet.Subtitles || overlay.sheet == TvPlayerSheet.SizeAndTiming
        if (subtitlesHidden && subtitleSheet) apply(overlay.closeSheet())
    }
    val appearance = rememberSubtitleAppearanceStore()
    TvSubtitleOffsetEffect(player, options.subtitleOffsetMillis)

    // Playing controls hide after three seconds without a key; paused, scrubbing or open-sheet ones stay.
    LaunchedEffect(overlay.controlsVisible, overlay.activity, playWhenReady, overlay.scrub == null, overlay.sheet) {
        if (overlay.autoHides && playWhenReady) {
            delay(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS)
            apply(overlay.hideTimedOut())
        }
    }
    val focus = remember { FocusRequester() }
    // Also after a sheet closes, so its dialog window hands the keys back to the player.
    LaunchedEffect(focus, overlay.sheet == null) { if (overlay.sheet == null) focus.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TV_PLAYER_TAG)
            .focusRequester(focus)
            .onKeyEvent { event -> overlay.onPlayerKey(event, player, chips, apply) }
            .focusable(),
    ) {
        if (target.mediaType == PlaybackMediaType.VIDEO) {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = playbackSurfaceType(Build.VERSION.SDK_INT, Build.HARDWARE),
            )
            if (choices.subtitlesOn) {
                SubtitleCueOverlay(
                    cues = rememberShownCues(player, cues, cueHistory, options.subtitleOffsetMillis),
                    videoAspectRatio = videoSize.displayAspectRatioOrNull(),
                    modifier = Modifier.align(Alignment.Center),
                    textScale = appearance.textSize.scale,
                    bottomPaddingFraction = subtitleBottomPadding(overlay.controlsVisible),
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
                chips = chips,
                chipLabels = tvPlayerChipLabels(choices, speed),
                focused = overlay.focusIn(chips),
            )
        }
    }

    val closeSheet = { apply(overlay.closeSheet()) }
    TvPlayerSheets(
        sheet = overlay.sheet,
        returnFocus = focus,
        choices = choices,
        sidecar = source.subtitles.sidecarTracks(),
        speed = speed,
        textSize = appearance.textSize,
        offsetMillis = options.subtitleOffsetMillis,
        actions = TvPlayerSheetActions(
            onAudio = { track ->
                options = options.copy(audio = AudioSelection.Track(track.identity))
                player.trackSelectionParameters = player.trackSelectionParameters.withAudioTrack(track)
                closeSheet()
            },
            onSubtitles = { selection ->
                options = options.copy(subtitles = selection)
                player.trackSelectionParameters = player.trackSelectionParameters.withSubtitleSelection(
                    selection = selection,
                    tracks = choices.subtitles,
                    textDefaults = defaultTrackSelection,
                )
                closeSheet()
            },
            onSizeAndTiming = { apply(overlay.openSizeAndTiming()) },
            onSpeed = { choice ->
                options = options.copy(speed = choice)
                player.setPlaybackSpeed(choice)
            },
            onTextSize = appearance::update,
            onOffset = { offset -> options = options.copy(subtitleOffsetMillis = offset) },
        ),
        onDismiss = { apply(overlay.back()) },
    )
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun TvSubtitleOffsetEffect(player: Player, offsetMillis: Long) {
    val lead = (-offsetMillis).coerceAtLeast(0L)
    val applied = remember(player) { mutableStateOf<Long?>(null) }
    LaunchedEffect(player, lead) {
        player.setSubtitleLead(lead, reread = applied.value != null)
        applied.value = lead
    }
}

@Composable
private fun rememberShownCues(
    player: Player,
    cues: List<Cue>,
    history: DelayedSubtitleCues,
    delayMillis: Long,
): List<Cue> {
    if (delayMillis <= 0L) return cues
    var shown by remember(player) { mutableStateOf(emptyList<Cue>()) }
    LaunchedEffect(player, delayMillis) {
        while (true) {
            shown = history.at(player.currentPosition, delayMillis)
            delay(DELAYED_CUE_TICK_MILLIS)
        }
    }
    return shown
}

private const val DELAYED_CUE_TICK_MILLIS = 50L
private const val SUBTITLES_ABOVE_CONTROLS = 0.22f

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun subtitleBottomPadding(controlsVisible: Boolean): Float =
    if (controlsVisible) SUBTITLES_ABOVE_CONTROLS else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION

private fun Player.perform(command: TvPlayerCommand, exit: () -> Unit, playNext: () -> Boolean) {
    when (command) {
        TvPlayerCommand.Play -> play()
        TvPlayerCommand.Pause -> pause()
        is TvPlayerCommand.SeekTo -> seekTo(command.positionMillis)
        TvPlayerCommand.Exit -> exit()
        TvPlayerCommand.PlayNext -> if (!playNext()) exit()
    }
}

/** A source resolved with the resume setting off carries no lease and writes nothing. */
private fun MediaItem.leasedBy(reporter: TvPlaybackReporter, fileId: Long, useStartFrom: Boolean): MediaItem {
    val lease = if (useStartFrom) reporter.lease(fileId) else null
    return lease?.let(this::withReportingLease) ?: this
}

private fun keepsScreenOn(playWhenReady: Boolean, playbackState: Int): Boolean =
    playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE

