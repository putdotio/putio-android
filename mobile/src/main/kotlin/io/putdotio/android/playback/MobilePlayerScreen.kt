package io.putdotio.android.playback

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import io.putdotio.sdk.files.PlaybackSubtitle
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.compose.ContentFrame
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.MobileErrorState
import io.putdotio.android.MobileKeyCommand
import io.putdotio.android.MobileShortcutScope
import io.putdotio.android.mobileKeyCommand
import io.putdotio.android.MobileLoadingState
import io.putdotio.android.R
import io.putdotio.sdk.files.PlaybackSource
import java.io.Closeable
import kotlinx.coroutines.delay

internal const val MOBILE_PLAYER_TAG = "mobile-player"
internal const val MOBILE_PLAYER_GESTURE_TAG = "mobile-player-gesture"
internal const val MOBILE_AUDIO_COVER_TAG = "mobile-audio-cover"
private const val MOBILE_CONTROLS_HIDE_DELAY_MILLIS = 3_000L

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobilePlayerScreen(
    state: PlaybackState,
    onRetry: () -> Unit,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoplayNextVideo: Boolean = false,
    onPlaybackEnded: () -> Unit = {},
    playerFactory: MobilePlayerFactory = DefaultMobilePlayerFactory,
    subtitleStartupPolicy: SubtitleStartupPolicy? = null,
    seekClock: () -> Long = SystemClock::uptimeMillis,
    onSourceRequired: (Long?) -> Unit = {},
    onResume: () -> Unit = {},
    onRestart: () -> Unit = {},
    onRefreshConversion: () -> Unit = {},
    onStartConversion: () -> Unit = {},
    nextEpisode: PlaybackTarget? = null,
    onNextEpisode: () -> Unit = {},
    neighbours: PlaybackNeighbours = PlaybackNeighbours(),
    onPlay: (PlaybackTarget) -> Unit = {},
    /** Hands the original to VLC when this player can't play it; null where VLC isn't offered. */
    onOpenInVlc: (() -> Unit)? = null,
) {
    // Like iOS, the window turns only for a landscape video; the last shape holds across autoplay.
    var landscapeVideo by rememberSaveable { mutableStateOf(false) }
    val preferences = rememberRetainedPlayerPreferences(state.target.fileId.value)
    val pictureInPicture = rememberPictureInPictureMode()
    if (state.target.mediaType == PlaybackMediaType.VIDEO) {
        MobileVideoWindow(fileId = state.target.fileId.value, landscape = landscapeVideo)
        // A closed window stopped the video; the next visit finds it paused instead of playing on.
        PictureInPictureDismissalEffect { preferences.resumeAfterLifecyclePause = false }
    }
    var keyboardNavigationActive by rememberSaveable(state.target.fileId.value) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .observeControlNavigation(
                onPointerNavigation = { keyboardNavigationActive = false },
                onKeyboardNavigation = { keyboardNavigationActive = true },
            ),
    ) {
        val isVideo = state.target.mediaType == PlaybackMediaType.VIDEO
        when (val content = state.content) {
            is PlaybackContent.Loading ->
                MobileLoadingState(stringResource(state.target.mediaType.loadingMessage()))

            is PlaybackContent.AwaitingResume -> MobileResumePlaybackDialog(
                startFromSeconds = content.source.startFromSeconds,
                onResume = onResume,
                onRestart = onRestart,
                onDismiss = onBack,
            )

            is PlaybackContent.Ready, PlaybackContent.Session ->
                MobileSessionPlayerHost(
                    mediaType = state.target.mediaType,
                    playerFactory = playerFactory,
                ) { sessionPlayer ->
                MobileReadyPlayer(
                    source = (content as? PlaybackContent.Ready)?.source,
                    useStartFrom = (content as? PlaybackContent.Ready)?.useStartFrom == true,
                    fileId = state.target.fileId.value,
                    title = state.target.name,
                    mediaType = state.target.mediaType,
                    sessionPlayer = sessionPlayer,
                    preferences = preferences,
                    sessionHandled = preferences.sessionHandled,
                    onSessionHandled = { preferences.sessionHandled = true },
                    startPositionMillis = preferences.positionMillis ?: state.resumePositionMillis,
                    resumeAfterLifecyclePause = preferences.resumeAfterLifecyclePause,
                    retainedSubtitleSelection = preferences.subtitleSelection,
                    subtitleStartupPolicy = subtitleStartupPolicy.orHiddenWhen(
                        (content as? PlaybackContent.Ready)?.subtitlesHidden == true,
                    ),
                    onPlaybackRetained = preferences::retainPlayback,
                    onPositionChanged = preferences::retainPosition,
                    onSubtitleSelectionChanged = { preferences.subtitleSelection = it },
                    keyboardNavigationActive = keyboardNavigationActive,
                    onKeyboardNavigation = { keyboardNavigationActive = true },
                    onPointerNavigation = { keyboardNavigationActive = false },
                    onPlayerFailure = onPlayerFailure,
                    autoplayNextVideo = autoplayNextVideo,
                    onPlaybackEnded = onPlaybackEnded,
                    playerFactory = playerFactory,
                    seekClock = seekClock,
                    onSourceRequired = onSourceRequired,
                    onBack = onBack,
                    onVideoAspectRatio = { landscapeVideo = it > 1f },
                    pictureInPicture = pictureInPicture,
                    sidecarSubtitles = (content as? PlaybackContent.Ready)?.source?.subtitles.sidecarTracks(),
                    nextEpisode = nextEpisode.takeIf { isVideo },
                    onNextEpisode = onNextEpisode,
                    neighbours = neighbours,
                    onPlay = onPlay,
                )
                }

            is PlaybackContent.FindingNext ->
                MobileLoadingState(stringResource(R.string.mobile_playback_finding_next))

            is PlaybackContent.NextFailed ->
                MobilePlaybackFailureState(
                    title = stringResource(R.string.mobile_playback_next_error_title),
                    failure = content.failure,
                    onRetry = onRetry,
                    video = isVideo,
                )

            PlaybackContent.Ended -> Unit

            is PlaybackContent.Conversion ->
                MobileConversionState(content, onRefreshConversion, onStartConversion)

            is PlaybackContent.Unsupported ->
                MobileEmptyState(
                    title = stringResource(R.string.mobile_playback_unsupported_title),
                    message = stringResource(R.string.mobile_playback_unsupported_message),
                )

            is PlaybackContent.Failed ->
                MobilePlaybackFailureState(
                    title = stringResource(state.target.mediaType.errorTitle()),
                    failure = content.failure,
                    onRetry = onRetry,
                    video = isVideo,
                    onOpenInVlc = onOpenInVlc,
                )
        }

        if (state.showsSeparateBack() && !pictureInPicture.value) {
            MobilePlayerSeparateBack(state, onBack, Modifier.align(Alignment.TopStart))
        }
    }
}

/**
 * Back where the player's own controls are not up: with the filename for video. Audio always has
 * it here, a caret down at the start of Now Playing's 64dp top bar, whatever state it is in.
 */
@Composable
private fun MobilePlayerSeparateBack(state: PlaybackState, onBack: () -> Unit, modifier: Modifier) {
    if (state.target.mediaType == PlaybackMediaType.VIDEO) {
        MobilePlayerFrameBar(title = state.target.name, onBack = onBack, modifier = modifier)
    } else {
        IconButton(
            onClick = onBack,
            modifier = modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 4.dp, top = 8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_caret_down),
                contentDescription = stringResource(R.string.mobile_action_back),
            )
        }
    }
}

private fun PlaybackState.showsSeparateBack(): Boolean =
    target.mediaType == PlaybackMediaType.AUDIO || content !is PlaybackContent.Ready

/**
 * Back and the filename over a video that is not playing yet or cannot: the resume prompt, the
 * conversion, a failure. It sits where the player's own top bar does.
 */
@Composable
private fun MobilePlayerFrameBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_arrow_left),
                contentDescription = stringResource(R.string.mobile_action_back),
            )
        }
        MobilePlayerTitle(title, Modifier.weight(1f))
    }
}

@UnstableApi
@Composable
private fun MobileReadyPlayer(
    source: PlaybackSource?,
    useStartFrom: Boolean,
    fileId: Long,
    title: String,
    mediaType: PlaybackMediaType,
    sessionPlayer: Media3Player?,
    preferences: RetainedPlayerPreferences,
    sessionHandled: Boolean,
    onSessionHandled: () -> Unit,
    startPositionMillis: Long?,
    resumeAfterLifecyclePause: Boolean,
    retainedSubtitleSelection: SubtitleSelection?,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    onPlaybackRetained: (RetainedPlayback) -> Unit,
    onPositionChanged: (Long) -> Unit,
    onSubtitleSelectionChanged: (SubtitleSelection) -> Unit,
    keyboardNavigationActive: Boolean,
    onKeyboardNavigation: () -> Unit,
    onPointerNavigation: () -> Unit,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    autoplayNextVideo: Boolean,
    onPlaybackEnded: () -> Unit,
    playerFactory: MobilePlayerFactory,
    seekClock: () -> Long,
    onSourceRequired: (Long?) -> Unit,
    onBack: () -> Unit,
    onVideoAspectRatio: (Float) -> Unit,
    pictureInPicture: State<Boolean>,
    sidecarSubtitles: List<PlaybackSubtitle>,
    nextEpisode: PlaybackTarget?,
    onNextEpisode: () -> Unit,
    neighbours: PlaybackNeighbours,
    onPlay: (PlaybackTarget) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lifecycleState by remember(lifecycle) { mutableStateOf(lifecycle.currentState) }
    val playerGeneration = remember(lifecycle) { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> lifecycleState = lifecycle.currentState }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return

    val sessionFileId = sessionPlayer?.sessionFileId(sessionHandled)
    val matchingSessionError = sessionPlayer?.playerErrorFor(fileId) != null
    if (source == null && sessionFileId != fileId && !matchingSessionError) {
        LaunchedEffect(sessionPlayer, fileId) { onSourceRequired(startPositionMillis) }
        MobileLoadingState(stringResource(R.string.mobile_playback_loading_audio))
        return
    }

    val initialPlayback = remember(source, title, mediaType, startPositionMillis) {
        source?.preparePlayback(title, mediaType, startPositionMillis)
    }
    val retainedPosition = rememberSaveable(fileId) {
        mutableLongStateOf(initialPlayback?.startPositionMillis ?: startPositionMillis ?: 0L)
    }
    val preparedPlayback = remember(source, title, mediaType, playerGeneration.intValue) {
        source?.preparePlayback(title, mediaType, retainedPosition.longValue)
    }
    val currentOnPlayerFailure = rememberUpdatedState(onPlayerFailure)
    val currentAutoplayNextVideo = rememberUpdatedState(autoplayNextVideo)
    val currentOnPlaybackEnded = rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackRetained = rememberUpdatedState(onPlaybackRetained)
    val currentOnPositionChanged = rememberUpdatedState(onPositionChanged)
    val currentSubtitleStartupPolicy = rememberUpdatedState(subtitleStartupPolicy)
    val currentPreferences = rememberUpdatedState(preferences)
    // A session player outlives this screen: it is never paused, released, or recreated here.
    val ownsPlayer = sessionPlayer == null
    val player = remember(context, lifecycle, playerFactory, playerGeneration.intValue, mediaType, sessionPlayer) {
        sessionPlayer ?: playerFactory.create(context, mediaType)
    }
    val isAudio = mediaType == PlaybackMediaType.AUDIO
    val playerState = remember(player) {
        ReadyPlayerState(
            player = player,
            ownsPlayer = ownsPlayer,
            positionObserver = if (ownsPlayer) playerFactory.observePositions(context, player) else null,
            sessionHandled = sessionHandled,
            keepScreenOn = !isAudio && player.shouldKeepScreenOn(),
            resumeAfterLifecyclePause = resumeAfterLifecyclePause,
        )
    }
    val currentOnVideoAspectRatio by rememberUpdatedState(onVideoAspectRatio)
    LaunchedEffect(playerState.videoSize) {
        playerState.videoSize.displayAspectRatioOrNull()?.let(currentOnVideoAspectRatio)
    }
    val controlsVisible = rememberSaveable(fileId) { mutableStateOf(true) }
    val controlsInteracting = remember { mutableStateOf(false) }
    val controlsMenuOpen = remember { mutableStateOf(false) }
    val controlsActivity = remember { mutableIntStateOf(0) }
    val pendingSeek = remember(player, fileId) { mutableStateOf<PendingSeek?>(null) }
    LaunchedEffect(player, resumeAfterLifecyclePause) {
        playerState.retainedPlayIntent = resumeAfterLifecyclePause
    }

    PlaybackPreparationEffect(
        playerState = playerState,
        preparedPlayback = preparedPlayback,
        source = source,
        fileId = fileId,
        useStartFrom = useStartFrom,
        startPositionMillis = startPositionMillis,
        retainedSubtitleSelection = retainedSubtitleSelection,
        subtitleStartupPolicy = subtitleStartupPolicy,
        preferences = preferences,
        playerFactory = playerFactory,
        retainedPosition = retainedPosition,
        currentOnPlaybackRetained = currentOnPlaybackRetained,
        currentOnPositionChanged = currentOnPositionChanged,
        currentOnPlayerFailure = currentOnPlayerFailure,
        onSessionHandled = onSessionHandled,
        onSourceRequired = onSourceRequired,
    )
    SubtitleStartupEffect(playerState, fileId, retainedSubtitleSelection, subtitleStartupPolicy)

    val touchExplorationEnabled = rememberTouchExplorationEnabled()
    ControlsVisibilityEffects(
        playerState = playerState,
        isAudio = isAudio,
        touchExplorationEnabled = touchExplorationEnabled,
        keyboardNavigationActive = keyboardNavigationActive,
        controlsVisible = controlsVisible,
        controlsInteracting = controlsInteracting,
        controlsMenuOpen = controlsMenuOpen,
        controlsActivity = controlsActivity,
    )
    SeekFeedbackTimeout(pendingSeek)
    HostViewEffects(playerState, controlsVisible, controlsActivity, onKeyboardNavigation)
    PlayerLifecycleEffects(
        playerState = playerState,
        playerGeneration = playerGeneration,
        retainedPosition = retainedPosition,
        onPlaybackRetained = onPlaybackRetained,
        onPositionChanged = onPositionChanged,
        currentOnPlaybackRetained = currentOnPlaybackRetained,
        currentOnPositionChanged = currentOnPositionChanged,
        pictureInPicture = pictureInPicture,
    )
    PlayerListenerEffect(
        playerState = playerState,
        isAudio = isAudio,
        retainedPosition = retainedPosition,
        controlsVisible = controlsVisible,
        pendingSeek = pendingSeek,
        currentPreferences = currentPreferences,
        currentSubtitleStartupPolicy = currentSubtitleStartupPolicy,
        currentAutoplayNextVideo = currentAutoplayNextVideo,
        currentOnPlaybackEnded = currentOnPlaybackEnded,
        currentOnPlayerFailure = currentOnPlayerFailure,
        currentOnPlaybackRetained = currentOnPlaybackRetained,
        currentOnPositionChanged = currentOnPositionChanged,
        pictureInPicture = pictureInPicture,
    )
    // The window's seek actions reach the screen's own seek, which owns the pending-seek bookkeeping.
    val pictureInPictureSeeks = remember(player) { SeekRelay() }
    val enterPictureInPicture = if (!isAudio) {
        // Whatever holds the screen on is playing; anything else shows Play, as Media3's button does.
        rememberMobilePictureInPicture(
            playing = playerState.keepScreenOn,
            videoSize = playerState.videoSize,
            videoFrame = playerState.videoFrame,
            onPlayingRequested = { playing ->
                if (!playerState.playerReleased) {
                    if (playing) Util.handlePlayButtonAction(player) else Util.handlePauseButtonAction(player)
                }
            },
            onSeekRequested = { direction ->
                if (!playerState.playerReleased) pictureInPictureSeeks.seek(direction)
            },
        )
    } else {
        null
    }

    MobileReadyPlayerContent(
        playerState = playerState,
        fileId = fileId,
        title = title,
        isAudio = isAudio,
        preferences = preferences,
        subtitleStartupPolicy = subtitleStartupPolicy,
        touchExplorationEnabled = touchExplorationEnabled,
        retainedPosition = retainedPosition,
        controlsVisible = controlsVisible,
        controlsInteracting = controlsInteracting,
        controlsMenuOpen = controlsMenuOpen,
        controlsActivity = controlsActivity,
        pendingSeek = pendingSeek,
        currentOnPositionChanged = currentOnPositionChanged,
        seekClock = seekClock,
        onSubtitleSelectionChanged = onSubtitleSelectionChanged,
        onKeyboardNavigation = onKeyboardNavigation,
        onPointerNavigation = onPointerNavigation,
        onBack = onBack,
        pictureInPicture = pictureInPicture.value,
        sidecarSubtitles = sidecarSubtitles,
        nextEpisode = nextEpisode,
        onNextEpisode = onNextEpisode,
        neighbours = neighbours,
        onPlay = onPlay,
        enterPictureInPicture = enterPictureInPicture,
        pictureInPictureSeeks = pictureInPictureSeeks,
    )
}

/**
 * What the screen tracks about one player. It is remembered with that player, so a recreated
 * player starts from fresh state while the route's saveable state carries over.
 */
@Stable
private class ReadyPlayerState(
    val player: Media3Player,
    val ownsPlayer: Boolean,
    val positionObserver: Closeable?,
    sessionHandled: Boolean,
    keepScreenOn: Boolean,
    resumeAfterLifecyclePause: Boolean,
) {
    var playerReleased by mutableStateOf(false)
    val defaultTrackSelection: TrackSelectionParameters = player.trackSelectionParameters

    // A route that already handled the session keeps an ended file ended; a route opened
    // fresh onto an ended file prepares it again.
    var activeFileId by mutableStateOf(player.sessionFileId(sessionHandled))
    var optionsInitialized by mutableStateOf(false)
    var cues by mutableStateOf(player.currentCues.cues)

    /** What showed, by position, for subtitles the viewer delayed. */
    val cueHistory = DelayedSubtitleCues()
    var textChoice = player.trackSelectionParameters.textChoice()
    var videoSize by mutableStateOf(player.videoSize)
    var playbackState by mutableIntStateOf(player.playbackState)
    var keepScreenOn by mutableStateOf(keepScreenOn)
    var playerWantsToPlay by mutableStateOf(player.playWhenReady)
    var retainedPlayIntent by mutableStateOf(resumeAfterLifecyclePause)
    var failurePositionMillis by mutableStateOf<Long?>(null)
    var endedReported by mutableStateOf(false)
    var seekWindow by mutableStateOf(player.currentSeekWindow())
    var nextSeekRequestId by mutableLongStateOf(0L)

    /** The video frame's full-screen bounds in the window, which picture-in-picture animates from. */
    var videoFrame by mutableStateOf<Rect?>(null)
}

@UnstableApi
@Composable
private fun PlaybackPreparationEffect(
    playerState: ReadyPlayerState,
    preparedPlayback: PreparedPlayback?,
    source: PlaybackSource?,
    fileId: Long,
    useStartFrom: Boolean,
    startPositionMillis: Long?,
    retainedSubtitleSelection: SubtitleSelection?,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    preferences: RetainedPlayerPreferences,
    playerFactory: MobilePlayerFactory,
    retainedPosition: MutableLongState,
    currentOnPlaybackRetained: State<(RetainedPlayback) -> Unit>,
    currentOnPositionChanged: State<(Long) -> Unit>,
    currentOnPlayerFailure: State<(PlaybackFailure, Long) -> Unit>,
    onSessionHandled: () -> Unit,
    onSourceRequired: (Long?) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = playerState.player
    LaunchedEffect(player, preparedPlayback) {
        val replacingFileId = playerState.activeFileId
        val sessionError = player.playerErrorFor(fileId)
        if (source == null && sessionError != null) {
            preferences.adoptPlaybackOptions(player)
            val position = player.currentPosition.coerceAtLeast(0L)
            playerState.failurePositionMillis = position
            playerRetentionUpdate(
                event = PlayerRetentionEvent.PlayerError,
                lifecycleState = lifecycle.currentState,
                positionMillis = position,
                playWhenReady = player.playWhenReady,
            ).dispatch(currentOnPlaybackRetained.value, currentOnPositionChanged.value)
            currentOnPlayerFailure.value(sessionError.toPlaybackFailure(), position)
            return@LaunchedEffect
        }
        if (!playerState.ownsPlayer && replacingFileId == fileId) {
            onSessionHandled()
            preferences.adoptPlaybackOptions(player)
            playerState.optionsInitialized = true
            // Returning to audio that kept playing: adopt the live position instead of restarting.
            val livePosition = player.currentPosition.coerceAtLeast(0L)
            retainedPosition.longValue = livePosition
            currentOnPositionChanged.value(livePosition)
            playerState.seekWindow = player.currentSeekWindow()
            playerState.playerWantsToPlay = player.playWhenReady
            playerState.retainedPlayIntent = player.playWhenReady
            return@LaunchedEffect
        }
        if (preparedPlayback == null) {
            onSourceRequired(startPositionMillis)
            return@LaunchedEffect
        }
        if (!playerState.ownsPlayer) onSessionHandled()
        val replacementPosition =
            replacementPositionMillis(
                activeFileId = replacingFileId,
                replacementFileId = fileId,
                livePositionMillis = player.currentPosition,
                preparedPositionMillis = preparedPlayback.startPositionMillis,
            )
        retainedPosition.longValue = replacementPosition
        currentOnPositionChanged.value(replacementPosition)
        if (replacingFileId != fileId || retainedSubtitleSelection == null) {
            player.trackSelectionParameters =
                restoreSubtitleSelection(
                    defaults = playerState.defaultTrackSelection,
                    retained = retainedSubtitleSelection,
                    startupPolicy = subtitleStartupPolicy,
                )
        }
        playerState.activeFileId = fileId
        // A file taking over session audio, from Now Playing's skips say, keeps the listener's speed, even
        // after the last file ended.
        if (!playerState.ownsPlayer && replacingFileId != fileId && player.mediaItemCount > 0) {
            preferences.playbackSpeed = player.playbackParameters.speed
        }
        player.setPlaybackSpeed(preferences.playbackSpeed)
        player.trackSelectionParameters = player.trackSelectionParameters.withAudioSelection(
            preferences.audioSelection,
            emptyList(),
        )
        playerState.optionsInitialized = true
        player.setMediaItem(playerFactory.reportableItem(preparedPlayback.mediaItem, useStartFrom), replacementPosition)
        player.prepare()
        playerState.seekWindow = player.currentSeekWindow()
        // Session audio plays behind a dialog or a stopped screen; only a private player waits.
        playerState.playerWantsToPlay =
            if (playerState.ownsPlayer) {
                lifecycleAllowsAutoplay(lifecycle.currentState, playerState.retainedPlayIntent)
            } else {
                playerState.retainedPlayIntent
            }
        player.playWhenReady = playerState.playerWantsToPlay
    }
}

@UnstableApi
@Composable
private fun SubtitleStartupEffect(
    playerState: ReadyPlayerState,
    fileId: Long,
    retainedSubtitleSelection: SubtitleSelection?,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
) {
    val player = playerState.player
    LaunchedEffect(player, playerState.activeFileId, subtitleStartupPolicy, retainedSubtitleSelection) {
        val policy = subtitleStartupPolicy ?: return@LaunchedEffect
        // Hiding also overrides a pick made before the settings arrived.
        if (playerState.activeFileId == fileId && (retainedSubtitleSelection == null || !policy.showSubtitles)) {
            val current = player.trackSelectionParameters
            val parameters =
                if (policy.showSubtitles && policy.autoSelectSubtitles) {
                    current.withSubtitleSelection(
                        SubtitleSelection.Automatic,
                        player.currentTracks.playbackSubtitleTracks(),
                        playerState.defaultTrackSelection,
                    )
                } else {
                    restoreSubtitleSelection(
                        defaults = current,
                        retained = null,
                        startupPolicy = policy,
                    )
                }
            if (parameters != player.trackSelectionParameters) {
                player.trackSelectionParameters = parameters
            }
        }
    }
}

@Composable
private fun ControlsVisibilityEffects(
    playerState: ReadyPlayerState,
    isAudio: Boolean,
    touchExplorationEnabled: Boolean,
    keyboardNavigationActive: Boolean,
    controlsVisible: MutableState<Boolean>,
    controlsInteracting: State<Boolean>,
    controlsMenuOpen: State<Boolean>,
    controlsActivity: MutableIntState,
) {
    val accessibilityManager = LocalAccessibilityManager.current
    // The tap layer has no accessibility click semantics, so a service enabled
    // mid-playback needs the controls back on screen to reach them.
    LaunchedEffect(touchExplorationEnabled) {
        controlsVisible.value = controlsVisibleForTouchExploration(controlsVisible.value, touchExplorationEnabled)
    }
    LaunchedEffect(keyboardNavigationActive) {
        if (keyboardNavigationActive) {
            controlsVisible.value = true
            controlsActivity.intValue += 1
        }
    }
    val player = playerState.player
    LaunchedEffect(
        player,
        controlsVisible.value,
        playerState.playerWantsToPlay,
        playerState.playbackState,
        controlsInteracting.value,
        keyboardNavigationActive,
        controlsMenuOpen.value,
        touchExplorationEnabled,
        controlsActivity.intValue,
    ) {
        if (!isAudio &&
            player.controlsShouldAutoHide(
                controlsVisible = controlsVisible.value,
                pointerInteracting = controlsInteracting.value,
                keyboardNavigationActive = keyboardNavigationActive,
                menuOpen = controlsMenuOpen.value,
                touchExplorationEnabled = touchExplorationEnabled,
            )
        ) {
            val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = MOBILE_CONTROLS_HIDE_DELAY_MILLIS,
                containsIcons = true,
                containsText = true,
                containsControls = true,
            ) ?: MOBILE_CONTROLS_HIDE_DELAY_MILLIS
            delay(timeout.coerceAtLeast(MOBILE_CONTROLS_HIDE_DELAY_MILLIS))
            controlsVisible.value = false
        }
    }
}

@Composable
private fun HostViewEffects(
    playerState: ReadyPlayerState,
    controlsVisible: MutableState<Boolean>,
    controlsActivity: MutableIntState,
    onKeyboardNavigation: () -> Unit,
) {
    val hostView = LocalView.current
    DisposableEffect(hostView, playerState.keepScreenOn) {
        // Captured, not re-read: by onDispose the state already holds the next value.
        val holdsScreen = playerState.keepScreenOn && !hostView.keepScreenOn
        if (holdsScreen) hostView.keepScreenOn = true
        onDispose {
            if (holdsScreen) hostView.keepScreenOn = false
        }
    }

    DisposableEffect(hostView) {
        val listener = ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                onKeyboardNavigation()
                controlsVisible.value = true
                controlsActivity.intValue += 1
            }
            false
        }
        ViewCompat.addOnUnhandledKeyEventListener(hostView, listener)
        onDispose { ViewCompat.removeOnUnhandledKeyEventListener(hostView, listener) }
    }
}

@Composable
private fun PlayerLifecycleEffects(
    playerState: ReadyPlayerState,
    playerGeneration: MutableIntState,
    retainedPosition: MutableLongState,
    onPlaybackRetained: (RetainedPlayback) -> Unit,
    onPositionChanged: (Long) -> Unit,
    currentOnPlaybackRetained: State<(RetainedPlayback) -> Unit>,
    currentOnPositionChanged: State<(Long) -> Unit>,
    pictureInPicture: State<Boolean>,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val player = playerState.player
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        // Entering picture-in-picture pauses the Activity and the video plays on in the window. The
        // Activity's own flag is already set when the pause arrives; mode listeners hear it later.
        // A paused split-screen or freeform window is still on screen too; Android's multi-window
        // guidance keeps video going until the window stops.
        val enteringPictureInPicture = pictureInPicture.value || activity?.isInPictureInPictureMode == true
        val stillVisible = enteringPictureInPicture || activity?.isInMultiWindowMode == true
        if (playerState.ownsPlayer && !playerState.playerReleased && !stillVisible) {
            val update =
                playerRetentionUpdate(
                    event = PlayerRetentionEvent.LifecyclePause,
                    lifecycleState = lifecycle.currentState,
                    positionMillis = player.currentPosition,
                    playWhenReady = player.playWhenReady,
                )
            playerState.retainedPlayIntent =
                (update as PlayerRetentionUpdate.Playback).retained.resumeAfterLifecyclePause
            retainedPosition.longValue = update.positionMillis
            update.dispatch(onPlaybackRetained, onPositionChanged)
            player.pause()
        }
    }
    PictureInPictureResumeEffect(playerState, pictureInPicture.value)
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (playerState.playerReleased) {
            playerGeneration.intValue += 1
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (playerState.ownsPlayer && !playerState.playerReleased && playerState.retainedPlayIntent) {
            player.play()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!playerState.playerReleased) {
            retainedPosition.longValue = player.currentPosition.coerceAtLeast(0L)
            RetainedPlayback(
                positionMillis = retainedPosition.longValue,
                resumeAfterLifecyclePause = playerState.retainedPlayIntent,
            ).let(currentOnPlaybackRetained.value)
            currentOnPositionChanged.value(retainedPosition.longValue)
            if (playerState.ownsPlayer) {
                playerState.playerReleased = true
                playerState.positionObserver?.close()
                player.release()
            }
        }
    }
}

/**
 * In the window the video plays whenever its play intent says so: a player prepared there, such
 * as autoplay's next video, and one a pause stopped before the Activity's flag was set.
 */
@Composable
private fun PictureInPictureResumeEffect(playerState: ReadyPlayerState, pictureInPicture: Boolean) {
    val player = playerState.player
    LaunchedEffect(player, pictureInPicture) {
        val pausedByLifecycle = playerState.ownsPlayer && playerState.retainedPlayIntent && !player.playWhenReady
        if (pictureInPicture && !playerState.playerReleased && pausedByLifecycle) player.play()
    }
}

@UnstableApi
@Composable
private fun PlayerListenerEffect(
    playerState: ReadyPlayerState,
    isAudio: Boolean,
    retainedPosition: MutableLongState,
    controlsVisible: MutableState<Boolean>,
    pendingSeek: MutableState<PendingSeek?>,
    currentPreferences: State<RetainedPlayerPreferences>,
    currentSubtitleStartupPolicy: State<SubtitleStartupPolicy?>,
    currentAutoplayNextVideo: State<Boolean>,
    currentOnPlaybackEnded: State<() -> Unit>,
    currentOnPlayerFailure: State<(PlaybackFailure, Long) -> Unit>,
    currentOnPlaybackRetained: State<(RetainedPlayback) -> Unit>,
    currentOnPositionChanged: State<(Long) -> Unit>,
    pictureInPicture: State<Boolean>,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val player = playerState.player
    DisposableEffect(player) {
        // A started split-screen or freeform window is on screen as a picture-in-picture one is, so a pause
        // or play made there, from a headset say, is what the next resume keeps.
        fun lifecycleState() = lifecycle.currentState.withPictureInPicture(
            pictureInPicture.value || activity?.isInMultiWindowMode == true,
        )
        // A picked track is found again in each new track list, and automatic subtitles find the
        // account's default. The pick is read live: tracks can change before recomposition
        // passes it back as retainedSubtitleSelection.
        fun resolveRetainedSubtitleSelection(tracks: List<PlaybackSubtitleTrack>) {
            val parameters = player.trackSelectionParameters.withSubtitleTracks(
                retained = currentPreferences.value.subtitleSelection,
                startupPolicy = currentSubtitleStartupPolicy.value,
                tracks = tracks,
                textDefaults = playerState.defaultTrackSelection,
            )
            if (parameters != player.trackSelectionParameters) {
                player.trackSelectionParameters = parameters
            }
        }
        val listener =
            object : Media3Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (playerState.playerReleased) return
                    if (!playerState.ownsPlayer) currentPreferences.value.adoptPlaybackOptions(player)
                    val errorPositionMillis = player.currentPosition.coerceAtLeast(0L)
                    retainedPosition.longValue = errorPositionMillis
                    playerState.failurePositionMillis = errorPositionMillis
                    playerRetentionUpdate(
                        event = PlayerRetentionEvent.PlayerError,
                        lifecycleState = lifecycleState(),
                        positionMillis = errorPositionMillis,
                        playWhenReady = player.playWhenReady,
                    ).dispatch(currentOnPlaybackRetained.value, currentOnPositionChanged.value)
                    currentOnPlayerFailure.value(
                        error.toPlaybackFailure(),
                        player.currentPosition,
                    )
                }

                override fun onCues(cueGroup: CueGroup) {
                    playerState.cues = cueGroup.cues
                    playerState.cueHistory.record(player.currentPosition, cueGroup.cues)
                }

                override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                    // Off or another subtitle track: what showed before must not replay after a delay.
                    val text = parameters.textChoice()
                    if (text != playerState.textChoice) playerState.cueHistory.clear()
                    playerState.textChoice = text
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Media3Player.PositionInfo,
                    newPosition: Media3Player.PositionInfo,
                    reason: Int,
                ) {
                    // What showed before a seek no longer lines up with the new position.
                    if (reason == Media3Player.DISCONTINUITY_REASON_SEEK) playerState.cueHistory.clear()
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    playerState.videoSize = size
                }

                override fun onTracksChanged(tracks: Tracks) {
                    resolveRetainedSubtitleSelection(tracks.playbackSubtitleTracks())
                    if (playerState.optionsInitialized) {
                        val parameters = player.trackSelectionParameters.withRetainedAudioSelection(
                            currentPreferences.value.audioSelection,
                            tracks.playbackAudioTracks(),
                        )
                        if (parameters != player.trackSelectionParameters) player.trackSelectionParameters = parameters
                    }
                }

                override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
                    if (playerState.optionsInitialized) currentPreferences.value.playbackSpeed = parameters.speed
                }

                override fun onPlaybackStateChanged(newPlaybackState: Int) {
                    playerState.playbackState = newPlaybackState
                    controlsVisible.value = controlsVisibleForPlaybackState(controlsVisible.value, newPlaybackState)
                    playerState.keepScreenOn = !isAudio && player.shouldKeepScreenOn()
                    if (newPlaybackState == Media3Player.STATE_ENDED) {
                        if (!playerState.endedReported) {
                            playerState.endedReported = true
                            if (currentAutoplayNextVideo.value) currentOnPlaybackEnded.value()
                        }
                    } else {
                        playerState.endedReported = false
                    }
                }

                override fun onEvents(
                    player: Media3Player,
                    events: Media3Player.Events,
                ) {
                    val updatedSeekWindow = player.currentSeekWindow()
                    pendingSeek.value =
                        pendingSeekAfterWindowUpdate(
                            pending = pendingSeek.value,
                            previousWindow = playerState.seekWindow,
                            updatedWindow = updatedSeekWindow,
                        )
                    playerState.seekWindow = updatedSeekWindow
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    playerState.keepScreenOn = !isAudio && player.shouldKeepScreenOn()
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    playerState.playerWantsToPlay = playWhenReady
                    playerState.keepScreenOn = !isAudio && player.shouldKeepScreenOn()
                    val update = playerRetentionUpdate(
                        event = PlayerRetentionEvent.PlayIntentChanged,
                        lifecycleState = lifecycleState(),
                        positionMillis = player.currentPosition,
                        playWhenReady = playWhenReady,
                    )
                    if (update is PlayerRetentionUpdate.Playback) {
                        playerState.retainedPlayIntent = update.retained.resumeAfterLifecyclePause
                    }
                    update.dispatch(currentOnPlaybackRetained.value, currentOnPositionChanged.value)
                }
            }
        player.addListener(listener)
        resolveRetainedSubtitleSelection(player.currentTracks.playbackSubtitleTracks())
        onDispose {
            player.removeListener(listener)
            if (playerState.playerReleased) return@onDispose
            retainedPosition.longValue =
                retainedPositionOnDispose(
                    failurePositionMillis = playerState.failurePositionMillis,
                    livePositionMillis = player.currentPosition,
                )
            playerRetentionUpdate(
                event = PlayerRetentionEvent.PlayerDisposed,
                lifecycleState = lifecycleState(),
                positionMillis = retainedPosition.longValue,
                playWhenReady = player.playWhenReady,
            ).dispatch(currentOnPlaybackRetained.value, currentOnPositionChanged.value)
            if (playerState.ownsPlayer) {
                playerState.positionObserver?.close()
                player.release()
            }
        }
    }
}

@UnstableApi
@Composable
private fun MobileReadyPlayerContent(
    playerState: ReadyPlayerState,
    fileId: Long,
    title: String,
    isAudio: Boolean,
    preferences: RetainedPlayerPreferences,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    touchExplorationEnabled: Boolean,
    retainedPosition: MutableLongState,
    controlsVisible: MutableState<Boolean>,
    controlsInteracting: MutableState<Boolean>,
    controlsMenuOpen: MutableState<Boolean>,
    controlsActivity: MutableIntState,
    pendingSeek: MutableState<PendingSeek?>,
    currentOnPositionChanged: State<(Long) -> Unit>,
    seekClock: () -> Long,
    onSubtitleSelectionChanged: (SubtitleSelection) -> Unit,
    onKeyboardNavigation: () -> Unit,
    onPointerNavigation: () -> Unit,
    onBack: () -> Unit,
    pictureInPicture: Boolean,
    sidecarSubtitles: List<PlaybackSubtitle>,
    nextEpisode: PlaybackTarget?,
    onNextEpisode: () -> Unit,
    neighbours: PlaybackNeighbours,
    onPlay: (PlaybackTarget) -> Unit,
    enterPictureInPicture: (() -> Unit)?,
    pictureInPictureSeeks: SeekRelay,
) {
    val player = playerState.player
    fun seek(direction: SeekDirection) {
        val currentWindow = player.currentSeekWindow()
        pendingSeek.value =
            pendingSeekAfterWindowUpdate(
                pending = pendingSeek.value,
                previousWindow = playerState.seekWindow,
                updatedWindow = currentWindow,
            )
        playerState.seekWindow = currentWindow
        if (!currentWindow.available) return
        val request =
            currentWindow.copy(intervals = if (isAudio) AudioSeekIntervals else VideoSeekIntervals).nextPendingSeek(
                previous = pendingSeek.value,
                currentPositionMillis = player.currentPosition,
                direction = direction,
                requestId = ++playerState.nextSeekRequestId,
                nowMillis = seekClock(),
            ) ?: return
        pendingSeek.value = request
        retainedPosition.longValue = request.targetPositionMillis
        currentOnPositionChanged.value(request.targetPositionMillis)
        player.seekTo(request.targetPositionMillis)
    }
    SideEffect { pictureInPictureSeeks.seek = ::seek }
    // The player holds keyboard focus itself, so Space and the arrows reach it before any control is
    // focused; Tab still walks the controls, and a focused control keeps the keys it uses.
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(keyFocus) { keyFocus.requestFocus() }

    val options = rememberPlayerOptionsSnapshot(player)
    var footerHeight by remember { mutableStateOf(0.dp) }
    val appearance = rememberSubtitleAppearanceStore()
    // The window shows only the video, so a sheet open when it starts goes with the controls.
    val sheetState = rememberSaveable(fileId) { mutableStateOf<MobileVideoSheet?>(null) }
    var sheet by sheetState
    SheetClosingEffect(sheetState, pictureInPicture, subtitlesHidden = subtitleStartupPolicy?.showSubtitles == false)
    SheetFocusEffect(sheet != null, controlsMenuOpen, keyFocus)
    SubtitleOffsetEffect(player, preferences.subtitleOffsetMillis)
    val optionModifier = Modifier.observeControlNavigation(onPointerNavigation, onKeyboardNavigation)

    Box(
        Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                videoKeyCommand(event.nativeKeyEvent, sheet, { sheet = sheet.parentSheet() }) { command ->
                    player.runKeyCommand(command, ::seek, onBack)
                }
            }
            .focusRequester(keyFocus)
            .focusTarget()
            .focusGroup()
            .testTag(MOBILE_PLAYER_TAG)
            .observePlayerControlInteraction(
                onInteractionChanged = {
                    controlsInteracting.value = it
                    if (it) onPointerNavigation()
                },
                onActivity = { controlsActivity.intValue += 1 },
            )
            .observePlayerControlKeyActivity {
                onKeyboardNavigation()
                controlsVisible.value = true
                controlsActivity.intValue += 1
            },
    ) {
        if (!isAudio) {
            MobileVideoLayers(
                playerState = playerState,
                fileId = fileId,
                touchExplorationEnabled = touchExplorationEnabled,
                controlsVisible = controlsVisible,
                onSeek = ::seek,
                onPointerNavigation = onPointerNavigation,
                pictureInPicture = pictureInPicture,
                subtitleTextScale = appearance.textSize.scale,
                subtitlesBesideSheet = sheet != null,
                subtitleClearance = footerHeight.takeIf { controlsVisible.value && sheet == null } ?: 0.dp,
                subtitleDelayMillis = preferences.subtitleOffsetMillis,
            )
        }
        if (isAudio) {
            MobileNowPlayingScreen(
                player = player,
                title = title,
                neighbours = neighbours,
                seekEnabled = playerState.seekWindow.available,
                onSeek = ::seek,
                onScrub = { pendingSeek.value = null },
                onPlay = onPlay,
                onAudioSelectionChanged = { preferences.audioSelection = it },
                modifier = Modifier.zIndex(2f).observeControlNavigation(onPointerNavigation, onKeyboardNavigation),
            )
            MobileSeekFeedbackLayer(pendingSeek.value)
        } else if (!pictureInPicture && sheet == null) {
            // The window shows only the video and its subtitles; its own menu carries the controls.
            MobilePlayerChrome(
                player = player,
                title = title,
                visible = controlsVisible.value,
                seekEnabled = playerState.seekWindow.available,
                onSeek = ::seek,
                onScrub = { pendingSeek.value = null },
                onBack = onBack,
                modifier = Modifier.zIndex(2f),
                topActions = { MobilePictureInPictureButton(enterPictureInPicture, optionModifier) },
                settings = { MobileVideoChips(options, subtitleStartupPolicy, { sheet = it }, optionModifier) },
                trailingChip = { MobileNextEpisodeChip(nextEpisode, onNextEpisode, optionModifier) },
                onFooterHeight = { footerHeight = it },
            )
            MobileSeekFeedbackLayer(pendingSeek.value)
        }
        if (!isAudio && !pictureInPicture) {
            MobileVideoSheets(
                sheet = sheet,
                onSheet = { sheet = it },
                player = player,
                options = options,
                defaultTrackSelection = playerState.defaultTrackSelection,
                sidecarSubtitles = sidecarSubtitles,
                appearance = appearance,
                preferences = preferences,
                onSubtitleSelectionChanged = onSubtitleSelectionChanged,
                modifier = Modifier.zIndex(2.5f),
                panelModifier = optionModifier,
            )
        }
    }
}

/**
 * Sooner subtitles: the text renderer reads ahead by the lead, and a playing player whose lead
 * changed reads its subtitles again. Later subtitles wait in the cue history instead.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun SubtitleOffsetEffect(player: Media3Player, offsetMillis: Long) {
    val lead = (-offsetMillis).coerceAtLeast(0L)
    val applied = remember(player) { mutableStateOf<Long?>(null) }
    LaunchedEffect(player, lead) {
        player.setSubtitleLead(lead, reread = applied.value != null)
        applied.value = lead
    }
}

/** The cues on screen: as the player shows them, or what it showed [delayMillis] ago. */
@Composable
private fun rememberShownCues(playerState: ReadyPlayerState, delayMillis: Long): List<Cue> {
    if (delayMillis <= 0L) return playerState.cues
    var shown by remember(playerState) { mutableStateOf(emptyList<Cue>()) }
    LaunchedEffect(playerState, delayMillis) {
        while (true) {
            val position = playerState.player.currentPosition
            shown = playerState.cueHistory.at(position, delayMillis)
            delay(DELAYED_CUE_TICK_MILLIS)
        }
    }
    return shown
}

private const val DELAYED_CUE_TICK_MILLIS = 50L

/**
 * The window shows only the video, so a sheet open when it starts goes with the controls; hidden
 * subtitles take their sheets with them.
 */
@Composable
private fun SheetClosingEffect(
    sheet: MutableState<MobileVideoSheet?>,
    pictureInPicture: Boolean,
    subtitlesHidden: Boolean,
) {
    LaunchedEffect(pictureInPicture, subtitlesHidden) {
        val open = sheet.value
        if (pictureInPicture || (subtitlesHidden && open?.isSubtitles == true)) sheet.value = null
    }
}

/** The open sheet holds the controls up; closing it hands keyboard focus back to the player. */
@Composable
private fun SheetFocusEffect(open: Boolean, controlsMenuOpen: MutableState<Boolean>, keyFocus: FocusRequester) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        controlsMenuOpen.value = open
        // So Space and the arrows reach the player again.
        if (!open && wasOpen) keyFocus.requestFocus()
        wasOpen = open
    }
}

/** The pending seek's feedback on the side it seeks toward. */
@Composable
private fun BoxScope.MobileSeekFeedbackLayer(request: PendingSeek?) {
    if (request == null) return
    // Identical text still needs a fresh accessibility event for each seek.
    key(request.requestId) {
        MobileSeekFeedback(
            request = request,
            modifier =
                Modifier
                    .align(
                        if (request.direction == SeekDirection.Backward) {
                            AbsoluteAlignment.CenterLeft
                        } else {
                            AbsoluteAlignment.CenterRight
                        },
                    )
                    .zIndex(3f)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 32.dp, vertical = 88.dp),
        )
    }
}

/** The subtitle part of a track selection: whether text is on, and which text track it pins. */
private fun TrackSelectionParameters.textChoice(): Pair<Boolean, List<TrackSelectionOverride>> =
    (C.TRACK_TYPE_TEXT in disabledTrackTypes) to overrides.values.filter { it.type == C.TRACK_TYPE_TEXT }

/** Hands the window's seek actions to the composed screen's seek. */
private class SeekRelay {
    var seek: (SeekDirection) -> Unit = {}
}

/**
 * Space plays or pauses, Left and Right seek like the 10-second buttons, and Escape or Backspace
 * leave. A held arrow keeps seeking; a held Space or Escape acts once. The release of a taken key is
 * taken too.
 */
internal fun playerKeyCommand(event: KeyEvent, run: (MobileKeyCommand) -> Unit): Boolean {
    val command = mobileKeyCommand(MobileShortcutScope.Player, event.keyCode, event.metaState) ?: return false
    val repeats = command == MobileKeyCommand.SeekBack || command == MobileKeyCommand.SeekForward
    if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || repeats)) run(command)
    return true
}

@UnstableApi
private fun Media3Player.runKeyCommand(command: MobileKeyCommand, seek: (SeekDirection) -> Unit, onBack: () -> Unit) {
    when (command) {
        MobileKeyCommand.PlayPause -> Util.handlePlayPauseButtonAction(this)
        MobileKeyCommand.SeekBack -> seek(SeekDirection.Backward)
        MobileKeyCommand.SeekForward -> seek(SeekDirection.Forward)
        MobileKeyCommand.Back -> onBack()
        else -> Unit
    }
}

@UnstableApi
@Composable
private fun BoxScope.MobileVideoLayers(
    playerState: ReadyPlayerState,
    fileId: Long,
    touchExplorationEnabled: Boolean,
    controlsVisible: MutableState<Boolean>,
    onSeek: (SeekDirection) -> Unit,
    onPointerNavigation: () -> Unit,
    pictureInPicture: Boolean,
    subtitleTextScale: Float,
    subtitlesBesideSheet: Boolean,
    subtitleClearance: Dp,
    subtitleDelayMillis: Long,
) {
    val player = playerState.player
    val currentPictureInPicture by rememberUpdatedState(pictureInPicture)
    ContentFrame(
        player = player,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                // Inside the window these are its own bounds; the next window still starts from full screen.
                if (!currentPictureInPicture) playerState.videoFrame = it.boundsInWindow()
            },
        surfaceType = playbackSurfaceType(Build.VERSION.SDK_INT, Build.HARDWARE),
    )
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(0.5f)
            .testTag(MOBILE_PLAYER_GESTURE_TAG),
    ) {
        // Separate physical regions keep taps across the midpoint as independent single taps.
        for (direction in SeekDirection.entries) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .align(
                        if (direction == SeekDirection.Backward) {
                            AbsoluteAlignment.CenterLeft
                        } else {
                            AbsoluteAlignment.CenterRight
                        },
                    )
                    .windowInsetsPadding(
                        WindowInsets.safeGestures.only(
                            WindowInsetsSides.Vertical +
                                if (direction == SeekDirection.Backward) {
                                    WindowInsetsSides.Left
                                } else {
                                    WindowInsetsSides.Right
                                },
                        ),
                    )
                    .pointerInput(player, fileId, playerState.seekWindow, touchExplorationEnabled) {
                        detectVideoTapGestures(
                            onDoubleTap = {
                                onPointerNavigation()
                                onSeek(direction)
                                if (!playerState.seekWindow.available) controlsVisible.value = true
                            },
                            onTap = {
                                onPointerNavigation()
                                controlsVisible.value = controlsVisibleAfterTap(
                                    controlsVisible = controlsVisible.value,
                                    playbackState = playerState.playbackState,
                                    touchExplorationEnabled = touchExplorationEnabled,
                                )
                            },
                        )
                    },
            )
        }
    }
    MobileSubtitleLayer(
        playerState = playerState,
        textScale = subtitleTextScale,
        besideSheet = subtitlesBesideSheet,
        clearance = subtitleClearance.takeUnless { pictureInPicture } ?: 0.dp,
        delayMillis = subtitleDelayMillis,
    )
    if (pictureInPicture) MobilePictureInPictureProgress(player, Modifier.align(Alignment.BottomStart).zIndex(3f))
}

/**
 * Beside an open side sheet the subtitles stay clear of the dimming: they lay out in the video that
 * still shows, at the video's height, so they wrap there at the size they play at and Size and
 * timing previews them as they will look.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileSubtitleLayer(
    playerState: ReadyPlayerState,
    textScale: Float,
    besideSheet: Boolean,
    clearance: Dp,
    delayMillis: Long,
) {
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(3f)) {
        val frame = DpSize(maxWidth, maxHeight)
        val aspectRatio = playerState.videoSize.displayAspectRatioOrNull()
        val bottomPadding by animateFloatAsState(
            subtitleBottomPadding(frame, aspectRatio, clearance),
            label = "subtitle-clearance",
        )
        val sheetWidth = if (besideSheet) MOBILE_PLAYER_SIDE_SHEET_WIDTH.coerceAtMost(maxWidth) else 0.dp
        val area = subtitleArea(frame, aspectRatio, sheetWidth)
        // A sheet as wide as the window leaves no video to show subtitles in.
        if (sheetWidth > 0.dp && area == null) return@BoxWithConstraints
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            SubtitleCueOverlay(
                cues = rememberShownCues(playerState, delayMillis),
                videoAspectRatio = area?.let { it.width / it.height } ?: aspectRatio,
                modifier = area?.let { Modifier.absoluteOffset(x = it.left).size(it.width, it.height) }
                    ?: Modifier.fillMaxSize(),
                textScale = textScale,
                bottomPaddingFraction = bottomPadding,
            )
        }
    }
}

/** The window's thin yellow line of how far the video has played. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobilePictureInPictureProgress(player: Media3Player, modifier: Modifier) {
    val progress = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1_000)
    val fraction = if (progress.durationMs > 0) {
        (progress.currentPositionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier
            .fillMaxWidth(fraction)
            .height(3.dp)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/**
 * Video owns a private player. Audio attaches to the session service's player, so the
 * content composes only once the connection resolves; a failed connection is recoverable.
 */
@Composable
private fun MobileSessionPlayerHost(
    mediaType: PlaybackMediaType,
    playerFactory: MobilePlayerFactory,
    content: @Composable (sessionPlayer: Media3Player?) -> Unit,
) {
    val context = LocalContext.current
    if (mediaType != PlaybackMediaType.AUDIO) {
        // Otherwise the notification and media buttons keep targeting the old audio.
        LaunchedEffect(context, playerFactory) { playerFactory.stopAudio(context) }
        content(null)
        return
    }
    var attempt by remember { mutableIntStateOf(0) }
    var connection by remember { mutableStateOf<Result<Media3Player>?>(null) }
    DisposableEffect(context, playerFactory, attempt) {
        connection = null
        val handle = playerFactory.connectAudio(context) { connection = it }
        onDispose { handle.closeQuietly() }
    }
    when (val result = connection) {
        null -> MobileLoadingState(stringResource(R.string.mobile_playback_loading_audio))
        else ->
            result.fold(
                onSuccess = { content(it) },
                onFailure = {
                    MobileErrorState(
                        title = stringResource(R.string.mobile_playback_error_title_audio),
                        message = stringResource(R.string.mobile_state_error_unavailable),
                        retryLabel = stringResource(R.string.mobile_action_retry),
                        onRetry = { attempt += 1 },
                    )
                },
            )
    }
}

// The file the session is currently prepared for, if any; idle sessions carry nothing.
internal fun Media3Player.activeSessionFileId(): Long? =
    if (playbackState == Media3Player.STATE_IDLE) {
        null
    } else {
        currentMediaItem?.mediaId?.toLongOrNull()
    }

// A file the session can carry on with; an ended one must be prepared again.
internal fun Media3Player.resumableSessionFileId(): Long? =
    activeSessionFileId()?.takeIf { playbackState != Media3Player.STATE_ENDED }

private fun Media3Player.sessionFileId(sessionHandled: Boolean): Long? =
    if (sessionHandled) activeSessionFileId() else resumableSessionFileId()

private fun Media3Player.playerErrorFor(fileId: Long): PlaybackException? =
    playerError?.takeIf { currentMediaItem?.mediaId == fileId.toString() }

@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

