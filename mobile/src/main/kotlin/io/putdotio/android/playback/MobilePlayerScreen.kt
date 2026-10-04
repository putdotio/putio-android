package io.putdotio.android.playback

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.MobileErrorState
import io.putdotio.android.MobileLoadingState
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import java.io.Closeable
import kotlin.math.roundToInt
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
) {
    // Like iOS, the window turns only for a landscape video; the last shape holds across autoplay.
    var landscapeVideo by rememberSaveable { mutableStateOf(false) }
    if (state.target.mediaType == PlaybackMediaType.VIDEO) {
        MobileVideoWindow(fileId = state.target.fileId.value, landscape = landscapeVideo)
    }
    val preferences = rememberRetainedPlayerPreferences(state.target.fileId.value)
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
        when (val content = state.content) {
            is PlaybackContent.Loading ->
                MobileLoadingState(stringResource(state.target.mediaType.loadingMessage()))

            is PlaybackContent.AwaitingResume -> MobileResumePlaybackDialog(
                title = state.target.name,
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
                    onPlayerFailure = { failure, positionMillis ->
                        onPlayerFailure(failure, positionMillis)
                    },
                    autoplayNextVideo = autoplayNextVideo,
                    onPlaybackEnded = onPlaybackEnded,
                    playerFactory = playerFactory,
                    seekClock = seekClock,
                    onSourceRequired = onSourceRequired,
                    onBack = onBack,
                    onVideoAspectRatio = { landscapeVideo = it > 1f },
                )
                }

            is PlaybackContent.FindingNext ->
                MobileLoadingState(stringResource(R.string.mobile_playback_finding_next))

            is PlaybackContent.NextFailed ->
                MobilePlaybackFailureState(
                    title = stringResource(R.string.mobile_playback_next_error_title),
                    failure = content.failure,
                    onRetry = onRetry,
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
                )
        }

        val showSeparateBack =
            state.target.mediaType == PlaybackMediaType.AUDIO || state.content !is PlaybackContent.Ready
        if (showSeparateBack) IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_arrow_left),
                contentDescription = stringResource(R.string.mobile_action_back),
            )
        }
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
    )

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
    var videoSize by mutableStateOf(player.videoSize)
    var playbackState by mutableIntStateOf(player.playbackState)
    var keepScreenOn by mutableStateOf(keepScreenOn)
    var playerWantsToPlay by mutableStateOf(player.playWhenReady)
    var retainedPlayIntent by mutableStateOf(resumeAfterLifecyclePause)
    var failurePositionMillis by mutableStateOf<Long?>(null)
    var endedReported by mutableStateOf(false)
    var seekWindow by mutableStateOf(player.currentSeekWindow())
    var nextSeekRequestId by mutableLongStateOf(0L)
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
        // Hiding also overrides a pick made before the settings arrived (#237).
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
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = playerState.player
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (playerState.ownsPlayer && !playerState.playerReleased) {
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
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = playerState.player
    DisposableEffect(player) {
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
                        lifecycleState = lifecycle.currentState,
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
                        lifecycleState = lifecycle.currentState,
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
                lifecycleState = lifecycle.currentState,
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
            currentWindow.nextPendingSeek(
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

    Box(
        Modifier
            .fillMaxSize()
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
            )
        }
        MobilePlayerChrome(
            player = player,
            title = title,
            isAudio = isAudio,
            visible = controlsVisible.value,
            seekEnabled = playerState.seekWindow.available,
            onSeek = ::seek,
            onScrub = { pendingSeek.value = null },
            onBack = onBack,
            modifier = Modifier.zIndex(2f),
            settings = {
                MobilePlaybackOptions(
                    player = player,
                    onAudioSelectionChanged = { preferences.audioSelection = it },
                    onMenuVisibilityChanged = { controlsMenuOpen.value = it },
                    onKeyboardNavigation = onKeyboardNavigation,
                    onPointerNavigation = onPointerNavigation,
                    directControls = !isAudio,
                )
                // hide_subtitles hides subtitles entirely, as every reference player does (#237).
                if (!isAudio && subtitleStartupPolicy?.showSubtitles != false) {
                    MobileSubtitleControls(
                        player = player,
                        defaultTrackSelection = playerState.defaultTrackSelection,
                        onSubtitleSelectionChanged = onSubtitleSelectionChanged,
                        onMenuVisibilityChanged = { controlsMenuOpen.value = it },
                        onKeyboardNavigation = onKeyboardNavigation,
                        onPointerNavigation = onPointerNavigation,
                        showLabel = true,
                    )
                }
            },
        )
        pendingSeek.value?.let { request ->
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
) {
    val player = playerState.player
    ContentFrame(
        player = player,
        modifier = Modifier.fillMaxSize(),
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
    SubtitleCueOverlay(
        cues = playerState.cues,
        videoAspectRatio = playerState.videoSize.displayAspectRatioOrNull(),
        modifier =
            Modifier
                .align(Alignment.Center)
                .zIndex(3f),
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

/** Try again repeats the same request; it shows only where that can succeed. */
@Composable
private fun MobilePlaybackFailureState(
    title: String,
    failure: PlaybackFailure,
    onRetry: () -> Unit,
) {
    val message = failure.apiReason ?: stringResource(failure.messageResource())
    if (failure.retryable) {
        MobileErrorState(
            title = title,
            message = message,
            retryLabel = stringResource(R.string.mobile_action_retry),
            onRetry = onRetry,
        )
    } else {
        MobileEmptyState(title = title, message = message)
    }
}

/**
 * Opening a video with no conversion requested starts one; queued and running conversions poll.
 * After a failure the viewer converts again, and checks again where the status waits.
 */
@Composable
private fun MobileConversionState(
    conversion: PlaybackContent.Conversion,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
) {
    PlaybackConversionPolling(conversion, onRefresh)
    val title = stringResource(R.string.mobile_playback_conversion_title)
    val message = if (conversion.starting) {
        stringResource(R.string.mobile_playback_conversion_starting)
    } else {
        conversion.state.message()
    }
    val action = when (conversion.action) {
        PlaybackConversionAction.ConvertAgain -> R.string.mobile_playback_convert_again to onStart
        PlaybackConversionAction.CheckAgain -> R.string.mobile_playback_check_again to onRefresh
        null -> null
    }
    if (action == null) {
        MobileEmptyState(title = title, message = message)
    } else {
        MobileErrorState(
            title = title,
            message = message,
            retryLabel = stringResource(action.first),
            onRetry = action.second,
            retryEnabled = conversion.refreshRequestId == null,
        )
    }
}

@Composable
private fun PlaybackConversionState.message(): String =
    when (this) {
        PlaybackConversionState.Queued -> stringResource(R.string.mobile_playback_conversion_queued)
        is PlaybackConversionState.Converting ->
            percent?.roundToInt()?.let {
                stringResource(R.string.mobile_playback_conversion_progress, "$it%")
            } ?: stringResource(R.string.mobile_playback_conversion_working)

        PlaybackConversionState.Completed -> stringResource(R.string.mobile_playback_conversion_completed)
        PlaybackConversionState.Failed -> stringResource(R.string.mobile_playback_conversion_failed)
        PlaybackConversionState.NotAvailable -> stringResource(R.string.mobile_playback_conversion_unavailable)
        is PlaybackConversionState.Unknown -> stringResource(R.string.mobile_playback_conversion_unknown)
    }

@StringRes
private fun PlaybackFailure.messageResource(): Int =
    when (this) {
        is PlaybackFailure.MediaCredentialUnavailable -> R.string.mobile_playback_error_credential
        is PlaybackFailure.MediaUnsupported -> R.string.mobile_playback_error_media_unsupported
        is PlaybackFailure.Putio -> when (failure) {
            is PutioFailure.AuthenticationRequired -> R.string.mobile_state_error_session
            is PutioFailure.AccessDenied -> R.string.mobile_playback_error_forbidden
            is PutioFailure.RateLimited -> R.string.mobile_state_error_rate_limited
            is PutioFailure.NetworkUnavailable -> R.string.mobile_state_error_message
            is PutioFailure.ApiRejected,
            is PutioFailure.InvalidResponse,
            is PutioFailure.Misconfigured,
            is PutioFailure.ServerUnavailable,
            is PutioFailure.Unexpected,
            -> R.string.mobile_state_error_unavailable
        }
    }

@StringRes
private fun PlaybackMediaType.loadingMessage(): Int =
    if (this == PlaybackMediaType.AUDIO) {
        R.string.mobile_playback_loading_audio
    } else {
        R.string.mobile_playback_loading
    }

@StringRes
private fun PlaybackMediaType.errorTitle(): Int =
    if (this == PlaybackMediaType.AUDIO) {
        R.string.mobile_playback_error_title_audio
    } else {
        R.string.mobile_playback_error_title
    }
