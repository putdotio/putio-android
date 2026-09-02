package io.putdotio.android

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.CaptioningManager
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.ViewCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.PlayerDefaults
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.hasSelectableSubtitles
import io.putdotio.android.playback.preparePlayback
import io.putdotio.android.playback.toPlaybackFailure
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val MOBILE_VIDEO_PLAYER_TAG = "mobile-video-player"
internal const val MOBILE_SUBTITLE_CUES_TAG = "mobile-subtitle-cues"
private const val MOBILE_CONTROLS_HIDE_DELAY_MILLIS = 3_000L

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileVideoPlayerScreen(
    state: PlaybackState,
    onRetry: () -> Unit,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    onBack: () -> Unit,
    autoplayNextVideo: Boolean = false,
    onPlaybackEnded: () -> Unit = {},
    modifier: Modifier = Modifier,
    playerFactory: MobilePlayerFactory = DefaultMobilePlayerFactory,
    subtitleStartupPolicy: SubtitleStartupPolicy? = null,
) {
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
                MobileLoadingState(stringResource(R.string.mobile_playback_loading))

            is PlaybackContent.Ready ->
                MobileReadyVideoPlayer(
                    source = content.source,
                    title = state.target.name,
                    startPositionMillis =
                        preferredPlaybackPosition(
                            retainedPositionMillis = preferences.positionMillis,
                            requestedPositionMillis = state.resumePositionMillis,
                        ),
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
                )

            is PlaybackContent.FindingNext ->
                MobileLoadingState(stringResource(R.string.mobile_playback_finding_next))

            is PlaybackContent.NextFailed ->
                MobileErrorState(
                    title = stringResource(R.string.mobile_playback_next_error_title),
                    message = stringResource(content.failure.messageResource()),
                    retryLabel = stringResource(R.string.mobile_action_retry),
                    onRetry = onRetry,
                )

            PlaybackContent.Ended ->
                MobileEmptyState(
                    title = stringResource(R.string.mobile_playback_ended_title),
                    message = stringResource(R.string.mobile_playback_ended_message),
                )

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
                    title = stringResource(R.string.mobile_playback_error_title),
                    message = stringResource(content.failure.messageResource()),
                    retryLabel = stringResource(R.string.mobile_action_retry),
                    onRetry = onRetry,
                )
        }

        IconButton(
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

@Stable
internal class RetainedPlayerPreferences(
    resumeAfterLifecyclePause: Boolean = true,
    subtitleSelection: SubtitleSelection? = null,
    positionMillis: Long? = null,
) {
    var resumeAfterLifecyclePause by mutableStateOf(resumeAfterLifecyclePause)
    var subtitleSelection by mutableStateOf(subtitleSelection)
    var positionMillis by mutableStateOf(positionMillis)

    fun retainPlayback(playback: RetainedPlayback) {
        positionMillis = playback.positionMillis
        resumeAfterLifecyclePause = playback.resumeAfterLifecyclePause
    }

    fun retainPosition(positionMillis: Long) {
        this.positionMillis = positionMillis.coerceAtLeast(0L)
    }
}

internal data class SubtitleStartupPolicy(
    val showSubtitles: Boolean,
    val autoSelectSubtitles: Boolean,
)

private val RetainedPlayerPreferencesSaver =
    Saver<RetainedPlayerPreferences, Bundle>(
        save = { preferences ->
            Bundle().apply {
                putBoolean("resumeAfterLifecyclePause", preferences.resumeAfterLifecyclePause)
                preferences.subtitleSelection?.let { putBundle("subtitleSelection", it.toBundle()) }
                preferences.positionMillis?.let { putLong("positionMillis", it) }
            }
        },
        restore = Bundle::toRetainedPlayerPreferences,
    )

internal fun Bundle.toRetainedPlayerPreferences(): RetainedPlayerPreferences =
    RetainedPlayerPreferences(
        resumeAfterLifecyclePause = getBoolean("resumeAfterLifecyclePause", true),
        subtitleSelection = getBundle("subtitleSelection")?.toSubtitleSelection(),
        positionMillis = getLong("positionMillis").takeIf { containsKey("positionMillis") },
    )

@Composable
internal fun rememberRetainedPlayerPreferences(fileId: Long): RetainedPlayerPreferences =
    rememberSaveable(fileId, saver = RetainedPlayerPreferencesSaver) {
        RetainedPlayerPreferences()
    }

@UnstableApi
@Composable
private fun MobileReadyVideoPlayer(
    source: PlaybackSource,
    title: String,
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

    val initialPlayback = remember(source, title, startPositionMillis) {
        source.preparePlayback(title, startPositionMillis)
    }
    var retainedPositionMillis by rememberSaveable(source.fileId) {
        mutableLongStateOf(initialPlayback.startPositionMillis)
    }
    val preparedPlayback = remember(source, title, playerGeneration) {
        source.preparePlayback(title, retainedPositionMillis)
    }
    val currentOnPlayerFailure = rememberUpdatedState(onPlayerFailure)
    val currentAutoplayNextVideo = rememberUpdatedState(autoplayNextVideo)
    val currentOnPlaybackEnded = rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackRetained = rememberUpdatedState(onPlaybackRetained)
    val currentOnPositionChanged = rememberUpdatedState(onPositionChanged)
    val currentRetainedSubtitleSelection = rememberUpdatedState(retainedSubtitleSelection)
    val player = remember(context, lifecycle, playerFactory, playerGeneration) { playerFactory.create(context) }
    var playerReleased by remember(player) { mutableStateOf(false) }
    val defaultTrackSelection = remember(player) { player.trackSelectionParameters }
    var activeFileId by remember(player) { mutableStateOf<Long?>(null) }
    var cues by remember(player) { mutableStateOf(player.currentCues.cues) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var keepScreenOn by remember(player) { mutableStateOf(player.shouldKeepScreenOn()) }
    var controlsVisible by rememberSaveable(source.fileId) { mutableStateOf(true) }
    var playerWantsToPlay by remember(player) { mutableStateOf(player.playWhenReady) }
    var retainedPlayIntent by remember(player) { mutableStateOf(resumeAfterLifecyclePause) }
    var controlsInteracting by remember { mutableStateOf(false) }
    var controlsMenuOpen by remember { mutableStateOf(false) }
    var controlsActivity by remember { mutableIntStateOf(0) }
    var failurePositionMillis by remember(player) { mutableStateOf<Long?>(null) }
    var endedReported by remember(player) { mutableStateOf(false) }
    val hostView = LocalView.current
    LaunchedEffect(player, resumeAfterLifecyclePause) {
        retainedPlayIntent = resumeAfterLifecyclePause
    }
    LaunchedEffect(player, preparedPlayback) {
        val replacingFileId = activeFileId
        val replacementPosition =
            replacementPositionMillis(
                activeFileId = replacingFileId,
                replacementFileId = source.fileId,
                livePositionMillis = player.currentPosition,
                preparedPositionMillis = preparedPlayback.startPositionMillis,
            )
        retainedPositionMillis = replacementPosition
        currentOnPositionChanged.value(replacementPosition)
        if (replacingFileId != source.fileId || retainedSubtitleSelection == null) {
            player.trackSelectionParameters =
                restoreSubtitleSelection(
                    defaults = defaultTrackSelection,
                    retained = retainedSubtitleSelection,
                    startupPolicy = subtitleStartupPolicy,
                    systemCaptionsEnabled = context.systemCaptionsEnabled(),
                )
        }
        activeFileId = source.fileId
        player.setMediaItem(preparedPlayback.mediaItem, replacementPosition)
        player.prepare()
        playerWantsToPlay =
            lifecycleAllowsAutoplay(lifecycle.currentState, retainedPlayIntent)
        player.playWhenReady = playerWantsToPlay
    }
    LaunchedEffect(player, activeFileId, subtitleStartupPolicy, retainedSubtitleSelection) {
        val policy = subtitleStartupPolicy ?: return@LaunchedEffect
        if (activeFileId == source.fileId && retainedSubtitleSelection == null) {
            val parameters =
                restoreSubtitleSelection(
                    defaults = defaultTrackSelection,
                    retained = null,
                    startupPolicy = policy,
                    systemCaptionsEnabled = context.systemCaptionsEnabled(),
                )
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
        controlsVisible,
        playerWantsToPlay,
        playbackState,
        controlsInteracting,
        keyboardNavigationActive,
        controlsMenuOpen,
        touchExplorationEnabled,
        controlsActivity,
    ) {
        if (controlsShouldAutoHide(
                controlsVisible = controlsVisible,
                playerWantsToPlay = playerWantsToPlay,
                playbackState = playbackState,
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
        if (!playerReleased) {
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
        if (!playerReleased && retainedPlayIntent) {
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
            playerReleased = true
            player.release()
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
                }

                override fun onPlaybackStateChanged(newPlaybackState: Int) {
                    playbackState = newPlaybackState
                    controlsVisible = controlsVisibleForPlaybackState(controlsVisible, newPlaybackState)
                    keepScreenOn = player.shouldKeepScreenOn()
                    if (newPlaybackState == Media3Player.STATE_ENDED) {
                        if (!endedReported) {
                            endedReported = true
                            if (currentAutoplayNextVideo.value) currentOnPlaybackEnded.value()
                        }
                    } else {
                        endedReported = false
                    }
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    keepScreenOn = player.shouldKeepScreenOn()
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    playerWantsToPlay = playWhenReady
                    keepScreenOn = player.shouldKeepScreenOn()
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
        resolveRetainedSubtitleSelection(player.mobileSubtitleTracks())
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
            player.release()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusGroup()
            .testTag(MOBILE_VIDEO_PLAYER_TAG)
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
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = playbackSurfaceType(Build.VERSION.SDK_INT, Build.HARDWARE),
        )
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(0.5f)
                .pointerInput(playbackState, touchExplorationEnabled) {
                    detectTapGestures {
                        onPointerNavigation()
                        controlsVisible =
                            controlsVisibleAfterTap(
                                controlsVisible = controlsVisible,
                                playbackState = playbackState,
                                touchExplorationEnabled = touchExplorationEnabled,
                            )
                    }
                },
        )
        MobileSubtitleCueOverlay(
            cues = cues,
            videoAspectRatio = videoSize.displayAspectRatioOrNull(),
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .zIndex(1f),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f)
                    .fillMaxWidth(),
        ) {
            PlayerDefaults.TopControls(
                player = player,
                visible = controlsVisible,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .observePlayerControlKeyActivity {
                            onKeyboardNavigation()
                            controlsVisible = true
                            controlsActivity += 1
                        }
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(8.dp),
            ) {
                if (source.hasSelectableSubtitles() && it != null) {
                    MobileSubtitleControls(
                        player = it,
                        defaultTrackSelection = defaultTrackSelection,
                        onSubtitleSelectionChanged = onSubtitleSelectionChanged,
                        onMenuVisibilityChanged = { controlsMenuOpen = it },
                        onKeyboardNavigation = onKeyboardNavigation,
                        onPointerNavigation = onPointerNavigation,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .zIndex(2f),
        ) {
            PlayerDefaults.CenterControls(
                player = player,
                visible = controlsVisible,
                modifier =
                    Modifier
                        .observePlayerControlKeyActivity {
                            onKeyboardNavigation()
                            controlsVisible = true
                            controlsActivity += 1
                        },
            )
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(2f)
                    .fillMaxWidth(),
        ) {
            PlayerDefaults.BottomControls(
                player = player,
                visible = controlsVisible,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .observePlayerControlKeyActivity {
                            onKeyboardNavigation()
                            controlsVisible = true
                            controlsActivity += 1
                        }
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(8.dp),
            )
        }
    }
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
        if (isPlayerControlActivity(event.type)) onActivity()
        false
    }

internal fun isPlayerControlActivity(type: KeyEventType): Boolean = type == KeyEventType.KeyDown

internal fun controlsShouldAutoHide(
    controlsVisible: Boolean,
    playerWantsToPlay: Boolean,
    playbackState: Int = Media3Player.STATE_READY,
    pointerInteracting: Boolean,
    keyboardNavigationActive: Boolean,
    menuOpen: Boolean,
    touchExplorationEnabled: Boolean,
): Boolean =
    controlsVisible &&
        playerWantsToPlay &&
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
@UnstableApi
internal fun MobileSubtitleCueOverlay(
    cues: List<Cue>,
    videoAspectRatio: Float?,
    modifier: Modifier = Modifier,
) {
    // Replacement players can deliver retained cues before their video dimensions.
    if (cues.isEmpty() || videoAspectRatio == null || !videoAspectRatio.isFinite() || videoAspectRatio <= 0f) return
    val context = LocalContext.current
    val renderer = remember(context) { SubtitleCueRenderer(context) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier =
                Modifier
                    .fitInsideAspectRatio(videoAspectRatio)
                    .testTag(MOBILE_SUBTITLE_CUES_TAG),
        ) {
            drawIntoCanvas { canvas ->
                renderer.draw(
                    cues = cues,
                    width = size.width.roundToInt(),
                    height = size.height.roundToInt(),
                    canvas = canvas.nativeCanvas,
                )
            }
        }
    }
}

@UnstableApi
internal class SubtitleCueRenderer(
    context: android.content.Context,
) {
    private val subtitleView =
        SubtitleView(context).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }
    private var renderedCues: List<Cue> = emptyList()
    private var renderedWidth = 0
    private var renderedHeight = 0

    fun draw(
        cues: List<Cue>,
        width: Int,
        height: Int,
        canvas: android.graphics.Canvas,
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (renderedCues != cues) {
            renderedCues = cues
            subtitleView.setCues(cues)
        }
        if (renderedWidth != safeWidth || renderedHeight != safeHeight) {
            renderedWidth = safeWidth
            renderedHeight = safeHeight
            subtitleView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(safeWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(safeHeight, android.view.View.MeasureSpec.EXACTLY),
            )
            subtitleView.layout(0, 0, safeWidth, safeHeight)
        }
        subtitleView.draw(canvas)
    }
}

internal fun VideoSize.displayAspectRatioOrNull(): Float? {
    if (width <= 0 || height <= 0 || !pixelWidthHeightRatio.isFinite() || pixelWidthHeightRatio <= 0f) {
        return null
    }
    return width.toFloat() * pixelWidthHeightRatio / height.toFloat()
}

private fun Modifier.fitInsideAspectRatio(aspectRatio: Float): Modifier =
    layout { measurable, constraints ->
        val fitted = fitInside(constraints.maxWidth, constraints.maxHeight, aspectRatio)
        val placeable =
            measurable.measure(
                androidx.compose.ui.unit.Constraints.fixed(fitted.width, fitted.height),
            )
        layout(fitted.width, fitted.height) { placeable.place(0, 0) }
    }

internal data class FittedVideoSize(val width: Int, val height: Int)

internal fun fitInside(
    availableWidth: Int,
    availableHeight: Int,
    aspectRatio: Float,
): FittedVideoSize {
    if (availableWidth <= 0 || availableHeight <= 0) return FittedVideoSize(0, 0)
    val availableRatio = availableWidth.toFloat() / availableHeight.toFloat()
    return if (aspectRatio >= availableRatio) {
        FittedVideoSize(availableWidth, (availableWidth / aspectRatio).roundToInt())
    } else {
        FittedVideoSize((availableHeight * aspectRatio).roundToInt(), availableHeight)
    }
}

internal fun lifecycleAllowsAutoplay(
    state: Lifecycle.State,
    resumeAfterLifecyclePause: Boolean = true,
): Boolean = resumeAfterLifecyclePause && state.isAtLeast(Lifecycle.State.RESUMED)

internal fun Media3Player.shouldKeepScreenOn(): Boolean =
    playbackKeepsScreenOn(playWhenReady, playbackState, playbackSuppressionReason)

internal fun playbackKeepsScreenOn(
    playWhenReady: Boolean,
    playbackState: Int,
    playbackSuppressionReason: Int = Media3Player.PLAYBACK_SUPPRESSION_REASON_NONE,
): Boolean =
    playWhenReady &&
        playbackSuppressionReason == Media3Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
        playbackState != Media3Player.STATE_IDLE &&
        playbackState != Media3Player.STATE_ENDED

internal data class RetainedPlayback(
    val positionMillis: Long,
    val resumeAfterLifecyclePause: Boolean,
)

internal fun retainPlaybackOnPause(
    positionMillis: Long,
    playWhenReady: Boolean,
): RetainedPlayback =
    RetainedPlayback(
        positionMillis = positionMillis.coerceAtLeast(0L),
        resumeAfterLifecyclePause = playWhenReady,
    )

internal enum class PlayerRetentionEvent {
    LifecyclePause,
    PlayerError,
    PlayerDisposed,
    PlayIntentChanged,
}

internal sealed interface PlayerRetentionUpdate {
    val positionMillis: Long

    data class Playback(val retained: RetainedPlayback) : PlayerRetentionUpdate {
        override val positionMillis: Long = retained.positionMillis
    }

    data class Position(override val positionMillis: Long) : PlayerRetentionUpdate
}

internal fun playerRetentionUpdate(
    event: PlayerRetentionEvent,
    lifecycleState: Lifecycle.State,
    positionMillis: Long,
    playWhenReady: Boolean,
): PlayerRetentionUpdate {
    val retained = retainPlaybackOnPause(positionMillis, playWhenReady)
    return if (event == PlayerRetentionEvent.LifecyclePause ||
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    ) {
        PlayerRetentionUpdate.Playback(retained)
    } else {
        PlayerRetentionUpdate.Position(retained.positionMillis)
    }
}

private fun PlayerRetentionUpdate.dispatch(
    onPlaybackRetained: (RetainedPlayback) -> Unit,
    onPositionChanged: (Long) -> Unit,
) {
    when (this) {
        is PlayerRetentionUpdate.Playback -> onPlaybackRetained(retained)
        is PlayerRetentionUpdate.Position -> onPositionChanged(positionMillis)
    }
}

internal fun replacementPositionMillis(
    activeFileId: Long?,
    replacementFileId: Long,
    livePositionMillis: Long,
    preparedPositionMillis: Long,
): Long =
    if (activeFileId == replacementFileId) {
        livePositionMillis.coerceAtLeast(0L)
    } else {
        preparedPositionMillis.coerceAtLeast(0L)
    }

internal fun preferredPlaybackPosition(
    retainedPositionMillis: Long?,
    requestedPositionMillis: Long?,
): Long? = retainedPositionMillis ?: requestedPositionMillis

internal fun retainedPositionOnDispose(
    failurePositionMillis: Long?,
    livePositionMillis: Long,
): Long = failurePositionMillis ?: livePositionMillis.coerceAtLeast(0L)

@Composable
private fun MobileSubtitleControls(
    player: Media3Player,
    defaultTrackSelection: TrackSelectionParameters,
    onSubtitleSelectionChanged: (SubtitleSelection) -> Unit,
    onMenuVisibilityChanged: (Boolean) -> Unit,
    onKeyboardNavigation: () -> Unit,
    onPointerNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tracks by remember(player) { mutableStateOf(player.mobileSubtitleTracks()) }
    var enabled by remember(player) {
        mutableStateOf(player.trackSelectionParameters.subtitlesEnabled(tracks))
    }
    var menuExpanded by remember(player) { mutableStateOf(false) }
    val currentOnMenuVisibilityChanged = rememberUpdatedState(onMenuVisibilityChanged)

    DisposableEffect(player) {
        onDispose { currentOnMenuVisibilityChanged.value(false) }
    }

    DisposableEffect(player) {
        val listener =
            object : Media3Player.Listener {
                override fun onTracksChanged(currentTracks: Tracks) {
                    tracks = currentTracks.mobileSubtitleTracks()
                    enabled = player.trackSelectionParameters.subtitlesEnabled(tracks)
                }

                override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                    tracks = player.mobileSubtitleTracks()
                    enabled = parameters.subtitlesEnabled(tracks)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        MobileSubtitleToggle(
            enabled = enabled,
            onToggle = { subtitlesEnabled ->
                enabled = subtitlesEnabled
                val selection =
                    if (subtitlesEnabled) SubtitleSelection.Automatic else SubtitleSelection.Off
                val parameters =
                    player.trackSelectionParameters.withSubtitleSelection(
                        selection = selection,
                        tracks = tracks,
                        textDefaults = defaultTrackSelection,
                    )
                player.trackSelectionParameters = parameters
                onSubtitleSelectionChanged(selection)
            },
        )
        if (tracks.size > 1) {
            Box {
                TextButton(
                    onClick = {
                        menuExpanded = true
                        onMenuVisibilityChanged(true)
                    },
                ) {
                    Text(stringResource(R.string.mobile_playback_choose_subtitles))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = {
                        menuExpanded = false
                        onMenuVisibilityChanged(false)
                    },
                    modifier =
                        Modifier
                            .observePlayerControlInteraction(
                                onInteractionChanged = { if (it) onPointerNavigation() },
                                onActivity = {},
                            ).observePlayerControlKeyActivity(onKeyboardNavigation),
                ) {
                    tracks.forEach { track ->
                        MobileSubtitleTrackOption(
                            track = track,
                            modifier =
                                Modifier
                                    .observePlayerControlInteraction(
                                        onInteractionChanged = { if (it) onPointerNavigation() },
                                        onActivity = {},
                                    ).observePlayerControlKeyActivity(onKeyboardNavigation),
                            onClick = {
                                enabled = true
                                val selection = SubtitleSelection.Track(track.identity)
                                val parameters =
                                    player.trackSelectionParameters.withSubtitleSelection(
                                        selection = selection,
                                        tracks = tracks,
                                        textDefaults = defaultTrackSelection,
                                    )
                                player.trackSelectionParameters = parameters
                                onSubtitleSelectionChanged(selection)
                                menuExpanded = false
                                onMenuVisibilityChanged(false)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MobileSubtitleTrackOption(
    track: MobileSubtitleTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenuItem(
        text = {
            Text(
                track.label
                    ?: stringResource(
                        R.string.mobile_playback_subtitle_track,
                        track.trackIndex + 1,
                    ),
            )
        },
        trailingIcon =
            if (track.selected) {
                { Text(stringResource(R.string.mobile_playback_subtitle_selected)) }
            } else {
                null
            },
        onClick = onClick,
        modifier =
            modifier.semantics {
                role = Role.RadioButton
                toggleableState = if (track.selected) ToggleableState.On else ToggleableState.Off
            },
    )
}

@Composable
internal fun MobileSubtitleToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = { onToggle(!enabled) },
        modifier =
            modifier.semantics {
                role = Role.Switch
                toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off
            },
    ) {
        Text(
            stringResource(
                if (enabled) R.string.mobile_playback_subtitles_on else R.string.mobile_playback_subtitles_off,
            ),
        )
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal data class MobileSubtitleTrack(
    val group: TrackGroup,
    val trackIndex: Int,
    val identity: SubtitleTrackIdentity = group.getFormat(trackIndex).toSubtitleTrackIdentity(),
    val label: String?,
    val selected: Boolean,
)

internal data class SubtitleTrackIdentity(
    val id: String?,
    val language: String?,
    val label: String?,
    val sampleMimeType: String?,
    val roleFlags: Int,
    val selectionFlags: Int,
    val accessibilityChannel: Int,
) {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun exactlyMatches(format: androidx.media3.common.Format): Boolean =
        id == format.id &&
            language == format.language &&
            label == format.label &&
            sampleMimeType == format.sampleMimeType &&
            roleFlags == format.roleFlags &&
            selectionFlags == format.selectionFlags &&
            accessibilityChannel == format.accessibilityChannel
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun List<MobileSubtitleTrack>.resolve(identity: SubtitleTrackIdentity): MobileSubtitleTrack? {
    val candidates = identity.id?.let { id -> filter { it.group.getFormat(it.trackIndex).id == id } }
    if (candidates?.size == 1) return candidates.single()
    return (candidates ?: this).singleOrNull { identity.exactlyMatches(it.group.getFormat(it.trackIndex)) }
}

internal sealed interface SubtitleSelection {
    data object Off : SubtitleSelection

    data object Automatic : SubtitleSelection

    data class Track(val identity: SubtitleTrackIdentity) : SubtitleSelection
}

internal fun Media3Player.mobileSubtitleTracks(): List<MobileSubtitleTrack> =
    currentTracks.mobileSubtitleTracks()

internal fun Tracks.mobileSubtitleTracks(): List<MobileSubtitleTrack> =
    groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) }
                .map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    MobileSubtitleTrack(
                        group = group.mediaTrackGroup,
                        trackIndex = trackIndex,
                        identity = format.toSubtitleTrackIdentity(),
                        label = format.label ?: format.language,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
        }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun androidx.media3.common.Format.toSubtitleTrackIdentity(): SubtitleTrackIdentity =
    SubtitleTrackIdentity(
        id = id,
        language = language,
        label = label,
        sampleMimeType = sampleMimeType,
        roleFlags = roleFlags,
        selectionFlags = selectionFlags,
        accessibilityChannel = accessibilityChannel,
    )

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun TrackSelectionParameters.withSubtitleSelection(
    selection: SubtitleSelection,
    tracks: List<MobileSubtitleTrack>,
    textDefaults: TrackSelectionParameters? = null,
): TrackSelectionParameters {
    val builder =
        buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setIgnoredTextSelectionFlags(0)
    return when (selection) {
        SubtitleSelection.Off ->
            builder
                .setSelectTextByDefault(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

        SubtitleSelection.Automatic ->
            builder.apply {
                textDefaults?.let { defaults ->
                    if (defaults.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager) {
                        setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings()
                    } else {
                        setPreferredTextLanguages(*defaults.preferredTextLanguages.toTypedArray())
                        setPreferredTextRoleFlags(defaults.preferredTextRoleFlags)
                    }
                    setPreferredTextLabels(*defaults.preferredTextLabels.toTypedArray())
                    setIgnoredTextSelectionFlags(defaults.ignoredTextSelectionFlags)
                    setSelectUndeterminedTextLanguage(defaults.selectUndeterminedTextLanguage)
                }
            }
                .setSelectTextByDefault(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()

        is SubtitleSelection.Track -> {
            val track = tracks.resolve(selection.identity)
            if (track == null) {
                builder
                    .setSelectTextByDefault(false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            } else {
                builder
                    .setSelectTextByDefault(true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(track.group, track.trackIndex))
                    .build()
            }
        }
    }
}

internal fun TrackSelectionParameters.withSubtitlesEnabled(enabled: Boolean): TrackSelectionParameters =
    withSubtitleSelection(
        selection = if (enabled) SubtitleSelection.Automatic else SubtitleSelection.Off,
        tracks = emptyList(),
    )

internal fun TrackSelectionParameters.subtitlesEnabled(tracks: List<MobileSubtitleTrack>): Boolean =
    C.TRACK_TYPE_TEXT !in disabledTrackTypes &&
        (selectTextByDefault || tracks.any(MobileSubtitleTrack::selected))

internal fun restoreSubtitleSelection(
    defaults: TrackSelectionParameters,
    retained: SubtitleSelection?,
    systemCaptionsEnabled: Boolean,
    startupPolicy: SubtitleStartupPolicy? = null,
): TrackSelectionParameters =
    retained?.let { defaults.withSubtitleSelection(it, emptyList(), defaults) }
        ?: startupPolicy?.let { policy ->
            when {
                !policy.showSubtitles ->
                    defaults.withSubtitleSelection(SubtitleSelection.Off, emptyList())
                policy.autoSelectSubtitles ->
                    defaults.withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), defaults)
                else ->
                    defaults.withForcedSubtitlesOnly()
            }
        }
        ?: if (systemCaptionsEnabled) {
            defaults.withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), defaults)
        } else {
            defaults.withForcedSubtitlesOnly()
        }

private fun TrackSelectionParameters.withForcedSubtitlesOnly(): TrackSelectionParameters =
    buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setPreferredTextLanguages()
        .setPreferredTextRoleFlags(0)
        .setPreferredTextLabels()
        .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .setSelectUndeterminedTextLanguage(false)
        .setSelectTextByDefault(false)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()

private fun SubtitleSelection.toBundle(): Bundle =
    Bundle().apply {
        when (this@toBundle) {
            SubtitleSelection.Off -> putString("kind", "off")
            SubtitleSelection.Automatic -> putString("kind", "automatic")
            is SubtitleSelection.Track -> {
                putString("kind", "track")
                putString("id", identity.id)
                putString("language", identity.language)
                putString("label", identity.label)
                putString("sampleMimeType", identity.sampleMimeType)
                putInt("roleFlags", identity.roleFlags)
                putInt("selectionFlags", identity.selectionFlags)
                putInt("accessibilityChannel", identity.accessibilityChannel)
            }
        }
    }

private fun Bundle.toSubtitleSelection(): SubtitleSelection? =
    when (getString("kind")) {
        "off" -> SubtitleSelection.Off
        "automatic" -> SubtitleSelection.Automatic
        "track" ->
            SubtitleSelection.Track(
                SubtitleTrackIdentity(
                    id = getString("id"),
                    language = getString("language"),
                    label = getString("label"),
                    sampleMimeType = getString("sampleMimeType"),
                    roleFlags = getInt("roleFlags"),
                    selectionFlags = getInt("selectionFlags"),
                    accessibilityChannel = getInt("accessibilityChannel"),
                ),
            )
        else -> null
    }

internal fun android.content.Context.systemCaptionsEnabled(): Boolean =
    (getSystemService(android.content.Context.CAPTIONING_SERVICE) as? CaptioningManager)?.isEnabled == true

internal fun interface MobilePlayerFactory {
    fun create(context: android.content.Context): Media3Player
}

internal object DefaultMobilePlayerFactory : MobilePlayerFactory {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun create(context: android.content.Context): Media3Player {
        val renderersFactory = DefaultRenderersFactory(context)
        if (requiresEmulatorCodecWorkaround(Build.VERSION.SDK_INT, Build.HARDWARE)) {
            // API 37's goldfish AVC codec can fail its memfd queue before decoding a frame.
            renderersFactory
                .setMediaCodecSelector(EmulatorMediaCodecSelector)
                .setEnableDecoderFallback(true)
        }
        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(MobileVideoAudioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}

internal val MobileVideoAudioAttributes: AudioAttributes =
    AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()

internal fun requiresEmulatorCodecWorkaround(
    sdkInt: Int,
    hardware: String,
): Boolean = sdkInt == 37 && (hardware == "ranchu" || hardware == "goldfish")

internal fun emulatorCodecPriority(codecName: String): Int =
    if (codecName.startsWith("c2.goldfish.")) 1 else 0

@UnstableApi
internal fun playbackSurfaceType(
    sdkInt: Int,
    hardware: String,
): Int =
    if (requiresEmulatorCodecWorkaround(sdkInt, hardware)) {
        SURFACE_TYPE_TEXTURE_VIEW
    } else {
        SURFACE_TYPE_SURFACE_VIEW
    }

@UnstableApi
private val EmulatorMediaCodecSelector =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        MediaCodecSelector.DEFAULT
            .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            .sortedBy { emulatorCodecPriority(it.name) }
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
