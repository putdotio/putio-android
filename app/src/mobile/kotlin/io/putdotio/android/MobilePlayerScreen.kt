package io.putdotio.android

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.ViewCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.preparePlayback
import io.putdotio.android.playback.toPlaybackFailure
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val MOBILE_PLAYER_TAG = "mobile-player"
internal const val MOBILE_PLAYER_GESTURE_TAG = "mobile-player-gesture"
internal const val MOBILE_AUDIO_COVER_TAG = "mobile-audio-cover"
internal const val MOBILE_SEEK_BACK_TAG = "mobile-seek-back"
internal const val MOBILE_SEEK_FORWARD_TAG = "mobile-seek-forward"
internal const val MOBILE_SEEK_FEEDBACK_TAG = "mobile-seek-feedback"
private const val MOBILE_CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private const val MOBILE_SEEK_FEEDBACK_DELAY_MILLIS = 800L
internal const val MOBILE_SEEK_INTERVAL_MILLIS = 10_000L

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
) {
    if (state.target.mediaType == PlaybackMediaType.VIDEO) {
        MobileVideoWindow(fileId = state.target.fileId.value)
    }
    val preferences = rememberRetainedPlayerPreferences(state.target.fileId.value)
    var keyboardNavigationActive by rememberSaveable(state.target.fileId.value) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .observePlayerControlInteraction(
                onInteractionChanged = { if (it) keyboardNavigationActive = false },
                onActivity = {},
            ).observePlayerControlKeyActivity { keyboardNavigationActive = true },
    ) {
        when (val content = state.content) {
            is PlaybackContent.Loading ->
                MobileLoadingState(
                    stringResource(
                        if (state.target.mediaType == PlaybackMediaType.AUDIO) {
                            R.string.mobile_playback_loading_audio
                        } else {
                            R.string.mobile_playback_loading
                        },
                    ),
                )

            is PlaybackContent.Ready, PlaybackContent.Session ->
                MobileSessionPlayerHost(
                    mediaType = state.target.mediaType,
                    playerFactory = playerFactory,
                ) { sessionPlayer ->
                MobileReadyPlayer(
                    source = (content as? PlaybackContent.Ready)?.source,
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
                    subtitleStartupPolicy = subtitleStartupPolicy,
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
                )
                }

            is PlaybackContent.FindingNext ->
                MobileLoadingState(stringResource(R.string.mobile_playback_finding_next))

            is PlaybackContent.NextFailed ->
                MobileErrorState(
                    title = stringResource(R.string.mobile_playback_next_error_title),
                    message = stringResource(content.failure.messageResource()),
                    retryLabel = stringResource(R.string.mobile_action_retry),
                    onRetry = onRetry,
                )

            PlaybackContent.Ended -> Unit

            is PlaybackContent.Conversion ->
                MobileErrorState(
                    title = stringResource(R.string.mobile_playback_conversion_title),
                    message = content.state.message(),
                    retryLabel = stringResource(R.string.mobile_playback_check_again),
                    onRetry = onRetry,
                )

            is PlaybackContent.Unsupported ->
                MobileEmptyState(
                    title = stringResource(R.string.mobile_playback_unsupported_title),
                    message = stringResource(R.string.mobile_playback_unsupported_message),
                )

            is PlaybackContent.Failed ->
                MobileErrorState(
                    title = stringResource(
                        if (state.target.mediaType == PlaybackMediaType.AUDIO) {
                            R.string.mobile_playback_error_title_audio
                        } else {
                            R.string.mobile_playback_error_title
                        },
                    ),
                    message = stringResource(content.failure.messageResource()),
                    retryLabel = stringResource(R.string.mobile_action_retry),
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
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lifecycleState by remember(lifecycle) { mutableStateOf(lifecycle.currentState) }
    var playerGeneration by remember(lifecycle) { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> lifecycleState = lifecycle.currentState }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return

    val sessionFileId = if (sessionHandled) sessionPlayer?.activeSessionFileId() else {
        sessionPlayer?.resumableSessionFileId()
    }
    val matchingSessionError = sessionPlayer?.currentMediaItem?.mediaId == fileId.toString() &&
        sessionPlayer.playerError != null
    if (source == null && sessionFileId != fileId && !matchingSessionError) {
        LaunchedEffect(sessionPlayer, fileId) { onSourceRequired(startPositionMillis) }
        MobileLoadingState(stringResource(R.string.mobile_playback_loading_audio))
        return
    }

    val initialPlayback = remember(source, title, mediaType, startPositionMillis) {
        source?.preparePlayback(title, mediaType, startPositionMillis)
    }
    var retainedPositionMillis by rememberSaveable(fileId) {
        mutableLongStateOf(initialPlayback?.startPositionMillis ?: startPositionMillis ?: 0L)
    }
    val preparedPlayback = remember(source, title, mediaType, playerGeneration) {
        source?.preparePlayback(title, mediaType, retainedPositionMillis)
    }
    val currentOnPlayerFailure = rememberUpdatedState(onPlayerFailure)
    val currentAutoplayNextVideo = rememberUpdatedState(autoplayNextVideo)
    val currentOnPlaybackEnded = rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackRetained = rememberUpdatedState(onPlaybackRetained)
    val currentOnPositionChanged = rememberUpdatedState(onPositionChanged)
    val currentRetainedSubtitleSelection = rememberUpdatedState(retainedSubtitleSelection)
    val currentPreferences = rememberUpdatedState(preferences)
    // A session player outlives this screen: it is never paused, released, or recreated here.
    val ownsPlayer = sessionPlayer == null
    val player = remember(context, lifecycle, playerFactory, playerGeneration, mediaType, sessionPlayer) {
        sessionPlayer ?: playerFactory.create(context, mediaType)
    }
    var playerReleased by remember(player) { mutableStateOf(false) }
    val defaultTrackSelection = remember(player) { player.trackSelectionParameters }
    // A route that already handled the session keeps an ended file ended; a route opened
    // fresh onto an ended file prepares it again.
    var activeFileId by remember(player) {
        mutableStateOf(if (sessionHandled) player.activeSessionFileId() else player.resumableSessionFileId())
    }
    var optionsInitialized by remember(player) { mutableStateOf(false) }
    var cues by remember(player) { mutableStateOf(player.currentCues.cues) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    val isAudio = mediaType == PlaybackMediaType.AUDIO
    var keepScreenOn by remember(player) { mutableStateOf(!isAudio && player.shouldKeepScreenOn()) }
    var controlsVisible by rememberSaveable(fileId) { mutableStateOf(true) }
    var playerWantsToPlay by remember(player) { mutableStateOf(player.playWhenReady) }
    var retainedPlayIntent by remember(player) { mutableStateOf(resumeAfterLifecyclePause) }
    var controlsInteracting by remember { mutableStateOf(false) }
    var controlsMenuOpen by remember { mutableStateOf(false) }
    var controlsActivity by remember { mutableIntStateOf(0) }
    var failurePositionMillis by remember(player) { mutableStateOf<Long?>(null) }
    var endedReported by remember(player) { mutableStateOf(false) }
    var seekWindow by remember(player) { mutableStateOf(player.currentSeekWindow()) }
    var pendingSeek by remember(player, fileId) { mutableStateOf<PendingSeek?>(null) }
    val accessibilityManager = LocalAccessibilityManager.current
    var nextSeekRequestId by remember(player) { mutableLongStateOf(0L) }
    val hostView = LocalView.current
    LaunchedEffect(player, resumeAfterLifecyclePause) {
        retainedPlayIntent = resumeAfterLifecyclePause
    }
    fun seek(direction: SeekDirection) {
        val currentWindow = player.currentSeekWindow()
        pendingSeek =
            pendingSeekAfterWindowUpdate(
                pending = pendingSeek,
                previousWindow = seekWindow,
                updatedWindow = currentWindow,
            )
        seekWindow = currentWindow
        if (!currentWindow.available) return
        val request =
            nextPendingSeek(
                previous = pendingSeek,
                currentPositionMillis = player.currentPosition,
                durationMillis = currentWindow.durationMillis,
                direction = direction,
                requestId = ++nextSeekRequestId,
                nowMillis = seekClock(),
            ) ?: return
        pendingSeek = request
        retainedPositionMillis = request.targetPositionMillis
        currentOnPositionChanged.value(request.targetPositionMillis)
        player.seekTo(request.targetPositionMillis)
    }

    LaunchedEffect(player, preparedPlayback) {
        val replacingFileId = activeFileId
        val sessionError = player.playerError
        if (source == null && player.currentMediaItem?.mediaId == fileId.toString() && sessionError != null) {
            preferences.adoptPlaybackOptions(player)
            val position = player.currentPosition.coerceAtLeast(0L)
            failurePositionMillis = position
            playerRetentionUpdate(
                event = PlayerRetentionEvent.PlayerError,
                lifecycleState = lifecycle.currentState,
                positionMillis = position,
                playWhenReady = player.playWhenReady,
            ).dispatch(currentOnPlaybackRetained.value, currentOnPositionChanged.value)
            currentOnPlayerFailure.value(sessionError.toPlaybackFailure(), position)
            return@LaunchedEffect
        }
        if (!ownsPlayer && replacingFileId == fileId) {
            onSessionHandled()
            preferences.adoptPlaybackOptions(player)
            optionsInitialized = true
            // Returning to audio that kept playing: adopt the live position instead of restarting.
            val livePosition = player.currentPosition.coerceAtLeast(0L)
            retainedPositionMillis = livePosition
            currentOnPositionChanged.value(livePosition)
            seekWindow = player.currentSeekWindow()
            playerWantsToPlay = player.playWhenReady
            retainedPlayIntent = player.playWhenReady
            return@LaunchedEffect
        }
        if (preparedPlayback == null) {
            onSourceRequired(startPositionMillis)
            return@LaunchedEffect
        }
        if (!ownsPlayer) onSessionHandled()
        val replacementPosition =
            replacementPositionMillis(
                activeFileId = replacingFileId,
                replacementFileId = fileId,
                livePositionMillis = player.currentPosition,
                preparedPositionMillis = preparedPlayback.startPositionMillis,
            )
        retainedPositionMillis = replacementPosition
        currentOnPositionChanged.value(replacementPosition)
        if (replacingFileId != fileId || retainedSubtitleSelection == null) {
            player.trackSelectionParameters =
                restoreSubtitleSelection(
                    defaults = defaultTrackSelection,
                    retained = retainedSubtitleSelection,
                    startupPolicy = subtitleStartupPolicy,
                    systemCaptionsEnabled = context.systemCaptionsEnabled(),
                )
        }
        activeFileId = fileId
        player.setPlaybackSpeed(preferences.playbackSpeed)
        player.trackSelectionParameters = player.trackSelectionParameters.withAudioSelection(
            preferences.audioSelection,
            emptyList(),
        )
        optionsInitialized = true
        player.setMediaItem(preparedPlayback.mediaItem, replacementPosition)
        player.prepare()
        seekWindow = player.currentSeekWindow()
        // Session audio plays behind a dialog or a stopped screen; only a private player waits.
        playerWantsToPlay =
            if (ownsPlayer) lifecycleAllowsAutoplay(lifecycle.currentState, retainedPlayIntent) else retainedPlayIntent
        player.playWhenReady = playerWantsToPlay
    }
    LaunchedEffect(player, activeFileId, subtitleStartupPolicy, retainedSubtitleSelection) {
        val policy = subtitleStartupPolicy ?: return@LaunchedEffect
        if (activeFileId == fileId && retainedSubtitleSelection == null) {
            val current = player.trackSelectionParameters
            val parameters =
                if (policy.showSubtitles && policy.autoSelectSubtitles) {
                    current.withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), defaultTrackSelection)
                } else {
                    restoreSubtitleSelection(
                        defaults = current,
                        retained = null,
                        startupPolicy = policy,
                        systemCaptionsEnabled = context.systemCaptionsEnabled(),
                    )
                }
            if (parameters != player.trackSelectionParameters) {
                player.trackSelectionParameters = parameters
            }
        }
    }

    val touchExplorationEnabled = rememberTouchExplorationEnabled()
    // The tap layer has no accessibility click semantics, so a service enabled
    // mid-playback needs the controls back on screen to reach them.
    LaunchedEffect(touchExplorationEnabled) {
        controlsVisible = controlsVisibleForTouchExploration(controlsVisible, touchExplorationEnabled)
    }
    LaunchedEffect(keyboardNavigationActive) {
        if (keyboardNavigationActive) {
            controlsVisible = true
            controlsActivity += 1
        }
    }
    LaunchedEffect(
        player,
        controlsVisible,
        playerWantsToPlay,
        playbackState,
        controlsInteracting,
        keyboardNavigationActive,
        controlsMenuOpen,
        touchExplorationEnabled,
        controlsActivity,
    ) {
        if (!isAudio &&
            player.controlsShouldAutoHide(
                controlsVisible = controlsVisible,
                pointerInteracting = controlsInteracting,
                keyboardNavigationActive = keyboardNavigationActive,
                menuOpen = controlsMenuOpen,
                touchExplorationEnabled = touchExplorationEnabled,
            )
        ) {
            delay(MOBILE_CONTROLS_HIDE_DELAY_MILLIS)
            controlsVisible = false
        }
    }
    LaunchedEffect(pendingSeek?.requestId) {
        val requestId = pendingSeek?.requestId ?: return@LaunchedEffect
        val feedbackTimeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = MOBILE_SEEK_FEEDBACK_DELAY_MILLIS,
            containsText = true,
        ) ?: MOBILE_SEEK_FEEDBACK_DELAY_MILLIS
        // Accumulation expiry lives on the request; this timer only hides the feedback.
        delay(feedbackTimeout.coerceAtLeast(MOBILE_SEEK_FEEDBACK_DELAY_MILLIS))
        // A new seek can arrive before recomposition cancels the previous timer.
        if (pendingSeek?.requestId == requestId) pendingSeek = null
    }

    DisposableEffect(hostView, keepScreenOn) {
        val inheritedKeepScreenOn = hostView.keepScreenOn
        if (keepScreenOn && !inheritedKeepScreenOn) hostView.keepScreenOn = true
        onDispose {
            if (keepScreenOn && !inheritedKeepScreenOn) hostView.keepScreenOn = false
        }
    }

    DisposableEffect(hostView) {
        val listener = ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                onKeyboardNavigation()
                controlsVisible = true
                controlsActivity += 1
            }
            false
        }
        ViewCompat.addOnUnhandledKeyEventListener(hostView, listener)
        onDispose { ViewCompat.removeOnUnhandledKeyEventListener(hostView, listener) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (ownsPlayer && !playerReleased) {
            val update =
                playerRetentionUpdate(
                    event = PlayerRetentionEvent.LifecyclePause,
                    lifecycleState = lifecycle.currentState,
                    positionMillis = player.currentPosition,
                    playWhenReady = player.playWhenReady,
                )
            retainedPlayIntent = (update as PlayerRetentionUpdate.Playback).retained.resumeAfterLifecyclePause
            retainedPositionMillis = update.positionMillis
            update.dispatch(onPlaybackRetained, onPositionChanged)
            player.pause()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (playerReleased) {
            playerGeneration += 1
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (ownsPlayer && !playerReleased && retainedPlayIntent) {
            player.play()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!playerReleased) {
            retainedPositionMillis = player.currentPosition.coerceAtLeast(0L)
            RetainedPlayback(
                positionMillis = retainedPositionMillis,
                resumeAfterLifecyclePause = retainedPlayIntent,
            ).let(currentOnPlaybackRetained.value)
            currentOnPositionChanged.value(retainedPositionMillis)
            if (ownsPlayer) {
                playerReleased = true
                player.release()
            }
        }
    }
    DisposableEffect(player) {
        fun resolveRetainedSubtitleSelection(tracks: List<MobileSubtitleTrack>) {
            val selection = currentRetainedSubtitleSelection.value as? SubtitleSelection.Track ?: return
            val parameters = player.trackSelectionParameters.withSubtitleSelection(selection, tracks)
            if (parameters != player.trackSelectionParameters) {
                player.trackSelectionParameters = parameters
            }
        }
        val listener =
            object : Media3Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (playerReleased) return
                    if (!ownsPlayer) currentPreferences.value.adoptPlaybackOptions(player)
                    val errorPositionMillis = player.currentPosition.coerceAtLeast(0L)
                    retainedPositionMillis = errorPositionMillis
                    failurePositionMillis = errorPositionMillis
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
                    cues = cueGroup.cues
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    videoSize = size
                }

                override fun onTracksChanged(tracks: Tracks) {
                    resolveRetainedSubtitleSelection(tracks.mobileSubtitleTracks())
                    if (optionsInitialized) {
                        val parameters = player.trackSelectionParameters.withRetainedAudioSelection(
                            currentPreferences.value.audioSelection,
                            tracks.mobileAudioTracks(),
                        )
                        if (parameters != player.trackSelectionParameters) player.trackSelectionParameters = parameters
                    }
                }

                override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
                    if (optionsInitialized) currentPreferences.value.playbackSpeed = parameters.speed
                }

                override fun onPlaybackStateChanged(newPlaybackState: Int) {
                    playbackState = newPlaybackState
                    controlsVisible = controlsVisibleForPlaybackState(controlsVisible, newPlaybackState)
                    keepScreenOn = !isAudio && player.shouldKeepScreenOn()
                    if (newPlaybackState == Media3Player.STATE_ENDED) {
                        if (!endedReported) {
                            endedReported = true
                            if (currentAutoplayNextVideo.value) currentOnPlaybackEnded.value()
                        }
                    } else {
                        endedReported = false
                    }
                }

                override fun onEvents(
                    player: Media3Player,
                    events: Media3Player.Events,
                ) {
                    val updatedSeekWindow = player.currentSeekWindow()
                    pendingSeek =
                        pendingSeekAfterWindowUpdate(
                            pending = pendingSeek,
                            previousWindow = seekWindow,
                            updatedWindow = updatedSeekWindow,
                        )
                    seekWindow = updatedSeekWindow
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    keepScreenOn = !isAudio && player.shouldKeepScreenOn()
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    playerWantsToPlay = playWhenReady
                    keepScreenOn = !isAudio && player.shouldKeepScreenOn()
                    val update = playerRetentionUpdate(
                        event = PlayerRetentionEvent.PlayIntentChanged,
                        lifecycleState = lifecycle.currentState,
                        positionMillis = player.currentPosition,
                        playWhenReady = playWhenReady,
                    )
                    if (update is PlayerRetentionUpdate.Playback) {
                        retainedPlayIntent = update.retained.resumeAfterLifecyclePause
                    }
                    update.dispatch(currentOnPlaybackRetained.value, currentOnPositionChanged.value)
                }
            }
        player.addListener(listener)
        resolveRetainedSubtitleSelection(player.currentTracks.mobileSubtitleTracks())
        onDispose {
            player.removeListener(listener)
            if (playerReleased) return@onDispose
            retainedPositionMillis =
                retainedPositionOnDispose(
                    failurePositionMillis = failurePositionMillis,
                    livePositionMillis = player.currentPosition,
                )
            playerRetentionUpdate(
                event = PlayerRetentionEvent.PlayerDisposed,
                lifecycleState = lifecycle.currentState,
                positionMillis = retainedPositionMillis,
                playWhenReady = player.playWhenReady,
            ).dispatch(currentOnPlaybackRetained.value, currentOnPositionChanged.value)
            if (ownsPlayer) player.release()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusGroup()
            .testTag(MOBILE_PLAYER_TAG)
            .observePlayerControlInteraction(
                onInteractionChanged = {
                    controlsInteracting = it
                    if (it) onPointerNavigation()
                },
                onActivity = { controlsActivity += 1 },
            )
            .observePlayerControlKeyActivity {
                onKeyboardNavigation()
                controlsVisible = true
                controlsActivity += 1
            },
    ) {
        if (!isAudio) {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = playbackSurfaceType(Build.VERSION.SDK_INT, Build.HARDWARE),
            )
        }
        if (!isAudio) Box(
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
                        .pointerInput(player, fileId, seekWindow, touchExplorationEnabled) {
                            detectVideoTapGestures(
                                onDoubleTap = {
                                    onPointerNavigation()
                                    seek(direction)
                                    if (!seekWindow.available) controlsVisible = true
                                },
                                onTap = {
                                    onPointerNavigation()
                                    controlsVisible = controlsVisibleAfterTap(
                                        controlsVisible = controlsVisible,
                                        playbackState = playbackState,
                                        touchExplorationEnabled = touchExplorationEnabled,
                                    )
                                },
                            )
                        },
                )
            }
        }
        if (!isAudio) MobileSubtitleCueOverlay(
            cues = cues,
            videoAspectRatio = videoSize.displayAspectRatioOrNull(),
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .zIndex(1f),
        )
        MobilePlayerChrome(
            player = player,
            title = title,
            isAudio = isAudio,
            visible = controlsVisible,
            seekEnabled = seekWindow.available,
            onSeek = ::seek,
            onScrub = { pendingSeek = null },
            onBack = onBack,
            modifier = Modifier.zIndex(2f),
            settings = {
                MobilePlaybackOptions(
                    player = player,
                    onAudioSelectionChanged = { preferences.audioSelection = it },
                    onMenuVisibilityChanged = { controlsMenuOpen = it },
                    onKeyboardNavigation = onKeyboardNavigation,
                    onPointerNavigation = onPointerNavigation,
                    directControls = !isAudio,
                )
                if (!isAudio) {
                    MobileSubtitleControls(
                        player = player,
                        defaultTrackSelection = defaultTrackSelection,
                        onSubtitleSelectionChanged = onSubtitleSelectionChanged,
                        onMenuVisibilityChanged = { controlsMenuOpen = it },
                        onKeyboardNavigation = onKeyboardNavigation,
                        onPointerNavigation = onPointerNavigation,
                        showLabel = true,
                    )
                }
            },
        )
        pendingSeek?.let { request ->
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

internal enum class SeekDirection {
    Backward,
    Forward,
}

internal data class PendingSeek(
    val direction: SeekDirection,
    val targetPositionMillis: Long,
    val accumulatedMillis: Long,
    val requestId: Long,
    // Monotonic time until which the next request stacks on this target.
    val accumulatesUntilMillis: Long,
)

internal fun PendingSeek.accumulatesAt(nowMillis: Long): Boolean = nowMillis < accumulatesUntilMillis

internal data class PlayerSeekWindow(
    val available: Boolean,
    val durationMillis: Long,
)

internal fun pendingSeekAfterWindowUpdate(
    pending: PendingSeek?,
    previousWindow: PlayerSeekWindow,
    updatedWindow: PlayerSeekWindow,
): PendingSeek? =
    pending?.takeIf {
        updatedWindow.available &&
            updatedWindow.durationMillis == previousWindow.durationMillis &&
            it.targetPositionMillis <= updatedWindow.durationMillis
    }

internal fun Media3Player.currentSeekWindow(): PlayerSeekWindow {
    val canReadCurrentItem = isCommandAvailable(Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
    val knownDuration = if (canReadCurrentItem) duration else C.TIME_UNSET
    return playerSeekWindow(
        canReadCurrentItem = canReadCurrentItem,
        durationMillis = knownDuration,
        seekable = canReadCurrentItem && isCurrentMediaItemSeekable,
        live = canReadCurrentItem && isCurrentMediaItemLive,
        canSeek = isCommandAvailable(Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
    )
}

internal fun playerSeekWindow(
    canReadCurrentItem: Boolean,
    durationMillis: Long,
    seekable: Boolean,
    live: Boolean,
    canSeek: Boolean,
): PlayerSeekWindow {
    val knownDuration = durationMillis.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
    return PlayerSeekWindow(
        available =
            canReadCurrentItem &&
                knownDuration > 0L &&
                seekable &&
                !live &&
                canSeek,
        durationMillis = knownDuration,
    )
}

internal fun nextPendingSeek(
    previous: PendingSeek?,
    currentPositionMillis: Long,
    durationMillis: Long,
    direction: SeekDirection,
    requestId: Long,
    nowMillis: Long,
): PendingSeek? {
    if (durationMillis <= 0L) return null
    // The window is measured from the previous request, not from when its effect started.
    val stacked = previous?.takeIf { it.accumulatesAt(nowMillis) }
    val basePosition = (stacked?.targetPositionMillis ?: currentPositionMillis).coerceIn(0L, durationMillis)
    val targetPosition =
        when (direction) {
            SeekDirection.Backward -> (basePosition - MOBILE_SEEK_INTERVAL_MILLIS).coerceAtLeast(0L)
            SeekDirection.Forward ->
                if (durationMillis - basePosition <= MOBILE_SEEK_INTERVAL_MILLIS) {
                    durationMillis
                } else {
                    basePosition + MOBILE_SEEK_INTERVAL_MILLIS
                }
        }
    val movedMillis =
        when (direction) {
            SeekDirection.Backward -> basePosition - targetPosition
            SeekDirection.Forward -> targetPosition - basePosition
        }
    if (movedMillis == 0L) return null
    val accumulated =
        if (stacked?.direction == direction) {
            stacked.accumulatedMillis + movedMillis
        } else {
            movedMillis
        }
    return PendingSeek(
        direction = direction,
        targetPositionMillis = targetPosition,
        accumulatedMillis = accumulated,
        requestId = requestId,
        accumulatesUntilMillis = nowMillis + MOBILE_SEEK_FEEDBACK_DELAY_MILLIS,
    )
}

@Composable
internal fun MobileSeekButton(
    direction: SeekDirection,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onVideo: Boolean = false,
) {
    val intervalSeconds = (MOBILE_SEEK_INTERVAL_MILLIS / 1_000L).toInt()
    val description =
        pluralStringResource(
            if (direction == SeekDirection.Backward) {
                R.plurals.mobile_playback_seek_back
            } else {
                R.plurals.mobile_playback_seek_forward
            },
            intervalSeconds,
            intervalSeconds,
        )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        ),
        modifier = modifier.requiredSize(if (onVideo) 48.dp else 56.dp).testTag(
            if (direction == SeekDirection.Backward) MOBILE_SEEK_BACK_TAG else MOBILE_SEEK_FORWARD_TAG,
        ).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.clearAndSetSemantics {}) {
            Icon(
                painter = painterResource(
                    if (direction == SeekDirection.Backward) {
                        R.drawable.ic_ph_arrow_counter_clockwise
                    } else {
                        R.drawable.ic_ph_arrow_clockwise
                    },
                ),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            // The number belongs to the fixed-size glyph; the accessible label carries the interval.
            Text("10", fontSize = with(LocalDensity.current) { 12.dp.toSp() })
        }
    }
}

@Composable
internal fun MobileSeekFeedback(
    request: PendingSeek,
    modifier: Modifier = Modifier,
) {
    val fractionalMovement = request.accumulatedMillis % 1_000L != 0L
    val seconds = (request.accumulatedMillis / 1_000L).toInt() + if (fractionalMovement) 1 else 0
    val message = when (request.direction) {
        SeekDirection.Backward -> if (fractionalMovement) {
            R.plurals.mobile_playback_seek_back_less_than
        } else {
            R.plurals.mobile_playback_seek_back
        }
        SeekDirection.Forward -> if (fractionalMovement) {
            R.plurals.mobile_playback_seek_forward_less_than
        } else {
            R.plurals.mobile_playback_seek_forward
        }
    }
    Text(
        text = pluralStringResource(message, seconds, seconds),
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.large,
                ).padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(MOBILE_SEEK_FEEDBACK_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

internal fun Modifier.observePlayerControlInteraction(
    onInteractionChanged: (Boolean) -> Unit,
    onActivity: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            var wasPressed = false
            while (true) {
                val isPressed =
                    awaitPointerEvent(PointerEventPass.Initial)
                        .changes
                        .any { it.pressed }
                if (isPressed && !wasPressed) onActivity()
                onInteractionChanged(isPressed)
                wasPressed = isPressed
            }
        }
    }

internal fun Modifier.observePlayerControlKeyActivity(onActivity: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) onActivity()
        false
    }

internal fun Media3Player.controlsShouldAutoHide(
    controlsVisible: Boolean,
    pointerInteracting: Boolean,
    keyboardNavigationActive: Boolean,
    menuOpen: Boolean,
    touchExplorationEnabled: Boolean,
): Boolean =
    controlsVisible &&
        playWhenReady &&
        playbackState != Media3Player.STATE_ENDED &&
        !pointerInteracting &&
        !keyboardNavigationActive &&
        !menuOpen &&
        !touchExplorationEnabled

internal fun controlsVisibleForTouchExploration(
    controlsVisible: Boolean,
    touchExplorationEnabled: Boolean,
): Boolean = controlsVisible || touchExplorationEnabled

internal fun controlsVisibleForPlaybackState(
    controlsVisible: Boolean,
    playbackState: Int,
): Boolean = controlsVisible || playbackState == Media3Player.STATE_ENDED

internal fun controlsVisibleAfterTap(
    controlsVisible: Boolean,
    playbackState: Int,
    touchExplorationEnabled: Boolean,
): Boolean =
    if (playbackState == Media3Player.STATE_ENDED || touchExplorationEnabled) {
        true
    } else {
        !controlsVisible
    }

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
        is PlaybackFailure.AuthenticationRequired -> R.string.mobile_state_error_session
        is PlaybackFailure.AccessDenied -> R.string.mobile_playback_error_forbidden
        is PlaybackFailure.RateLimited -> R.string.mobile_state_error_rate_limited
        is PlaybackFailure.NetworkUnavailable -> R.string.mobile_state_error_message
        is PlaybackFailure.MediaCredentialUnavailable -> R.string.mobile_playback_error_credential
        is PlaybackFailure.ApiRejected,
        is PlaybackFailure.InvalidResponse,
        is PlaybackFailure.Misconfigured,
        is PlaybackFailure.ServerUnavailable,
        is PlaybackFailure.Unexpected,
        -> R.string.mobile_state_error_unavailable
    }
