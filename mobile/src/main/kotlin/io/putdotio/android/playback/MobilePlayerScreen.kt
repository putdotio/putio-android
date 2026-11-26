package io.putdotio.android.playback

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.DeviceInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
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
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.files.hasVlc
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import java.io.Closeable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.putdotio.android.MobileSnackbarHost
import io.putdotio.sdk.files.PlaybackSubtitles
import androidx.compose.material3.SnackbarHostState
import io.putdotio.android.MobileIconButton

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
    onOriginalRejected: (PlaybackFallbackReason, Long) -> Boolean = { _, _ -> false },
    onFirstFrame: () -> Unit = {},
    onOriginalChosen: ((Long) -> Unit)? = null,
    autoplayNextVideo: Boolean = false,
    rotateToLandscape: Boolean = true,
    onPlaybackEnded: () -> Unit = {},
    analytics: PlaybackAnalyticsReporter = PlaybackAnalyticsReporter.None,
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
    upNext: PlaybackUpNextLookup = PlaybackUpNextLookup.Off,
    onPlayUpNext: (PlaybackTarget) -> Unit = {},
    neighbours: PlaybackNeighbours = PlaybackNeighbours(),
    onPlay: (PlaybackTarget) -> Unit = {},
    onOpenInVlc: (() -> Unit)? = null,
    cast: MobileCastController? = null,
    castMedia: MobileCastMediaLoader? = null,
    onCastEnded: (() -> Unit)? = null,
) {
    var landscapeVideo by rememberSaveable { mutableStateOf(false) }
    val preferences = rememberRetainedPlayerPreferences(state.target.fileId.value)
    val pictureInPicture = rememberPictureInPictureMode()
    if (state.target.mediaType == PlaybackMediaType.VIDEO) {
        MobileVideoWindow(
            fileId = state.target.fileId.value,
            landscape = landscapeVideo,
            rotateToLandscape = rotateToLandscape,
        )
        PictureInPictureDismissalEffect { preferences.resumeAfterLifecyclePause = false }
    }
    val keyboardNavigation = rememberSaveable(state.target.fileId.value) { mutableStateOf(false) }
    var keyboardNavigationActive by keyboardNavigation
    val castPicker = rememberSaveable { mutableStateOf(false) }
    val playerCast = rememberPlayerCast(cast, castMedia) { castPicker.value = true }
    val readyContent: @Composable () -> Unit = {
        MobileReadyContent(
            state = state,
            playerCast = playerCast,
            preferences = preferences,
            playerFactory = playerFactory,
            subtitleStartupPolicy = subtitleStartupPolicy,
            keyboardNavigation = keyboardNavigation,
            onPlayerFailure = onPlayerFailure,
            onOriginalRejected = onOriginalRejected,
            onFirstFrame = onFirstFrame,
            onOriginalChosen = onOriginalChosen,
            autoplayNextVideo = autoplayNextVideo,
            onPlaybackEnded = onPlaybackEnded,
            analytics = analytics,
            seekClock = seekClock,
            onSourceRequired = onSourceRequired,
            onBack = onBack,
            onVideoAspectRatio = { landscapeVideo = it > 1f },
            pictureInPicture = pictureInPicture,
            nextEpisode = nextEpisode.takeIf { state.target.mediaType == PlaybackMediaType.VIDEO },
            onNextEpisode = onNextEpisode,
            upNext = upNext.whenAutoplaying(autoplayNextVideo, state.target.mediaType),
            onPlayUpNext = onPlayUpNext,
            neighbours = neighbours,
            onPlay = onPlay,
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .observeControlNavigation(
                onPointerNavigation = { keyboardNavigationActive = false },
                onKeyboardNavigation = { keyboardNavigationActive = true },
            ),
    ) {
        Box(Modifier.fillMaxSize().hiddenFromAccessibility(castPicker.value)) {
            val isVideo = state.target.mediaType == PlaybackMediaType.VIDEO
            val shown = state.shownContent(playerCast, onResume, onCastEnded)
            when (val content = shown) {
                is PlaybackContent.Loading ->
                    MobileLoadingState(stringResource(state.target.mediaType.loadingMessage()))

                is PlaybackContent.AwaitingResume -> MobileResumePlaybackDialog(
                    startFromSeconds = content.source.startFromSeconds,
                    onResume = onResume,
                    onRestart = onRestart,
                    onDismiss = onBack,
                )

                is PlaybackContent.Ready, PlaybackContent.Session -> readyContent()

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
                    MobileConversionState(
                        content,
                        onRefreshConversion,
                        onStartConversion,
                        onCancel = onBack,
                        onPlayInVlc = onOpenInVlc?.takeIf { LocalContext.current.hasVlc() },
                    )

                is PlaybackContent.Unsupported ->
                    MobileEmptyState(
                        title = stringResource(R.string.mobile_playback_unsupported_title),
                        message = stringResource(
                            if (isVideo) {
                                R.string.mobile_playback_unsupported_video_message
                            } else {
                                R.string.mobile_playback_unsupported_message
                            },
                        ),
                    )

                is PlaybackContent.Failed ->
                    MobilePlaybackFailureState(
                        title = stringResource(content.errorTitle(state.target.mediaType)),
                        failure = content.failure,
                        onRetry = onRetry,
                        video = isVideo,
                        onOpenInVlc = onOpenInVlc,
                    )
            }

            if (state.showsSeparateBack(shown) && !pictureInPicture.value) {
                MobilePlayerSeparateBack(state, onBack, Modifier.align(Alignment.TopStart))
            }
        }
        MobileCastPickerHost(cast, castPicker, pictureInPicture.value, Modifier.zIndex(4f))
    }
}

@Composable
private fun PlaybackState.shownContent(
    playerCast: PlayerCast,
    onResume: () -> Unit,
    onCastEnded: (() -> Unit)?,
): PlaybackContent {
    if (onCastEnded != null && convertedForCast && !playerCast.session) {
        LaunchedEffect(target.fileId) { onCastEnded() }
        return ReopeningOriginal
    }
    val prompt = content as? PlaybackContent.AwaitingResume
    val attached = playerCast.attaches(target.fileId.value) && content.attachable
    if (prompt != null && (attached || playerCast.handsBack(target.fileId.value))) {
        LaunchedEffect(prompt) { onResume() }
    }
    return if (attached) PlaybackContent.Session else content
}

private val ReopeningOriginal = PlaybackContent.Loading(PlaybackRequestId(0L))

private val PlaybackContent.attachable: Boolean
    get() = this is PlaybackContent.Loading || this is PlaybackContent.AwaitingResume ||
        this is PlaybackContent.Conversion || this is PlaybackContent.Failed

private fun Modifier.hiddenFromAccessibility(hidden: Boolean): Modifier =
    if (hidden) clearAndSetSemantics {} else this

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileReadyContent(
    state: PlaybackState,
    playerCast: PlayerCast,
    preferences: RetainedPlayerPreferences,
    playerFactory: MobilePlayerFactory,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    keyboardNavigation: MutableState<Boolean>,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    onOriginalRejected: (PlaybackFallbackReason, Long) -> Boolean,
    onFirstFrame: () -> Unit,
    onOriginalChosen: ((Long) -> Unit)?,
    autoplayNextVideo: Boolean,
    onPlaybackEnded: () -> Unit,
    analytics: PlaybackAnalyticsReporter,
    seekClock: () -> Long,
    onSourceRequired: (Long?) -> Unit,
    onBack: () -> Unit,
    onVideoAspectRatio: (Float) -> Unit,
    pictureInPicture: State<Boolean>,
    nextEpisode: PlaybackTarget?,
    onNextEpisode: () -> Unit,
    upNext: PlaybackUpNextLookup,
    onPlayUpNext: (PlaybackTarget) -> Unit,
    neighbours: PlaybackNeighbours,
    onPlay: (PlaybackTarget) -> Unit,
) {
    val casting = playerCast.casting
    if (casting != null) {
        MobileCastPlayback(
            state = state,
            cast = casting.controller,
            castMedia = casting.media,
            deviceName = casting.deviceName,
            preferences = preferences,
            playerFactory = playerFactory,
            subtitleStartupPolicy = subtitleStartupPolicy,
            castButton = playerCast.button,
            onBack = onBack,
            onSourceRequired = onSourceRequired,
            onOriginalRejected = onOriginalRejected,
            neighbours = neighbours,
            onPlay = onPlay,
            onPlaybackEnded = onPlaybackEnded,
            analytics = analytics,
        )
        return
    }
    val handover = rememberCastHandover(playerCast, state.target.fileId.value, preferences)
    LaunchedEffect(analytics) { analytics.localShown() }
    val ready = state.content as? PlaybackContent.Ready
    MobileSessionPlayerHost(
        mediaType = state.target.mediaType,
        playerFactory = playerFactory,
    ) { sessionPlayer ->
        MobileReadyPlayer(
            source = ready?.source,
            offline = ready?.offline == true,
            useStartFrom = ready?.useStartFrom == true,
            fileId = state.target.fileId.value,
            title = state.target.name,
            mediaType = state.target.mediaType,
            sessionPlayer = sessionPlayer,
            preferences = preferences,
            sessionHandled = preferences.sessionHandled,
            onSessionHandled = { preferences.sessionHandled = true },
            startPositionMillis = handover?.positionMillis ?: preferences.positionMillis
                ?: state.resumePositionMillis,
            resumeAfterLifecyclePause = handover?.playing ?: preferences.resumeAfterLifecyclePause,
            retainedSubtitleSelection = preferences.subtitleSelection,
            subtitleStartupPolicy = subtitleStartupPolicy.orHiddenWhen(ready?.subtitlesHidden == true),
            onPlaybackRetained = preferences::retainPlayback,
            onPositionChanged = preferences::retainPosition,
            onSubtitleSelectionChanged = { preferences.subtitleSelection = it },
            keyboardNavigationActive = keyboardNavigation.value,
            onKeyboardNavigation = { keyboardNavigation.value = true },
            onPointerNavigation = { keyboardNavigation.value = false },
            onPlayerFailure = onPlayerFailure,
            onOriginalRejected = onOriginalRejected,
            onFirstFrame = onFirstFrame,
            onOriginalChosen = onOriginalChosen,
            autoplayNextVideo = autoplayNextVideo,
            onPlaybackEnded = onPlaybackEnded,
            analytics = analytics,
            playerFactory = playerFactory,
            seekClock = seekClock,
            onSourceRequired = onSourceRequired,
            onBack = onBack,
            onVideoAspectRatio = onVideoAspectRatio,
            pictureInPicture = pictureInPicture,
            playerSubtitles = ready?.source?.playerSubtitles() ?: PlaybackSubtitles.None,
            nextEpisode = nextEpisode,
            onNextEpisode = onNextEpisode,
            upNext = upNext,
            onPlayUpNext = onPlayUpNext,
            neighbours = neighbours,
            onPlay = onPlay,
            castButton = playerCast.button,
        )
    }
}

@Composable
private fun MobilePlayerSeparateBack(state: PlaybackState, onBack: () -> Unit, modifier: Modifier) {
    if (state.target.mediaType == PlaybackMediaType.VIDEO) {
        MobilePlayerFrameBar(title = state.target.name, onBack = onBack, modifier = modifier)
    } else {
        Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 4.dp, top = 8.dp)) {
            MobileIconButton(stringResource(R.string.mobile_action_back), R.drawable.ic_ph_caret_down, onBack)
        }
    }
}

private fun PlaybackState.showsSeparateBack(shown: PlaybackContent): Boolean =
    target.mediaType == PlaybackMediaType.AUDIO || (shown !is PlaybackContent.Ready && shown != PlaybackContent.Session)

@Composable
internal fun MobilePlayerFrameBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MobileIconButton(stringResource(R.string.mobile_action_back), R.drawable.ic_ph_arrow_left, onBack)
        MobilePlayerTitle(title, Modifier.weight(1f))
    }
}

@UnstableApi
@Composable
private fun MobileReadyPlayer(
    source: PlaybackSource?,
    offline: Boolean,
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
    onOriginalRejected: (PlaybackFallbackReason, Long) -> Boolean,
    onFirstFrame: () -> Unit,
    onOriginalChosen: ((Long) -> Unit)?,
    autoplayNextVideo: Boolean,
    onPlaybackEnded: () -> Unit,
    analytics: PlaybackAnalyticsReporter,
    playerFactory: MobilePlayerFactory,
    seekClock: () -> Long,
    onSourceRequired: (Long?) -> Unit,
    onBack: () -> Unit,
    onVideoAspectRatio: (Float) -> Unit,
    pictureInPicture: State<Boolean>,
    playerSubtitles: PlaybackSubtitles,
    nextEpisode: PlaybackTarget?,
    onNextEpisode: () -> Unit,
    upNext: PlaybackUpNextLookup,
    onPlayUpNext: (PlaybackTarget) -> Unit,
    neighbours: PlaybackNeighbours,
    onPlay: (PlaybackTarget) -> Unit,
    castButton: @Composable (Modifier) -> Unit,
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

    val appName = stringResource(DesignR.string.app_name)
    val initialPlayback = remember(source, title, mediaType, startPositionMillis, appName) {
        source?.preparePlayback(title, mediaType, startPositionMillis, offline, appName)
    }
    val retainedPosition = rememberSaveable(fileId) {
        mutableLongStateOf(initialPlayback?.startPositionMillis ?: startPositionMillis ?: 0L)
    }
    val preparedPlayback = remember(source, offline, title, mediaType, playerGeneration.intValue, appName) {
        source?.preparePlayback(title, mediaType, retainedPosition.longValue, offline, appName)
    }
    val currentOnPlayerFailure = rememberUpdatedState(onPlayerFailure)
    val currentOnOriginalRejected = rememberUpdatedState(onOriginalRejected)
    val currentOnFirstFrame = rememberUpdatedState(onFirstFrame)
    val currentAutoplayNextVideo = rememberUpdatedState(autoplayNextVideo)
    val currentOnReachedEnd = rememberUpdatedState<() -> Unit> { analytics.reachedEnd() }
    val currentOnPlaybackRetained = rememberUpdatedState(onPlaybackRetained)
    val currentOnPositionChanged = rememberUpdatedState(onPositionChanged)
    val currentSubtitleStartupPolicy = rememberUpdatedState(subtitleStartupPolicy)
    val currentPreferences = rememberUpdatedState(preferences)
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
    val upNextEnd = rememberMobileUpNextEnd(
        fileId = fileId,
        playbackState = playerState.playbackState,
        lookup = upNext,
        pictureInPicture = pictureInPicture.value,
        autoplay = autoplayNextVideo,
        onPlaybackEnded = onPlaybackEnded,
        onPlay = onPlayUpNext,
    )
    val markEnded = rememberUpdatedState<() -> Unit> { upNextEnd.ended = true }
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
    SubtitlesUnavailableNotice(playerState, source.takeUnless { isAudio })

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
        currentOnPlaybackEnded = markEnded,
        currentOnReachedEnd = currentOnReachedEnd,
        currentOnPlayerFailure = currentOnPlayerFailure,
        currentOnOriginalRejected = currentOnOriginalRejected,
        currentOnFirstFrame = currentOnFirstFrame,
        currentOnPlaybackRetained = currentOnPlaybackRetained,
        currentOnPositionChanged = currentOnPositionChanged,
        pictureInPicture = pictureInPicture,
    )
    val pictureInPictureSeeks = remember(player) { SeekRelay() }
    val enterPictureInPicture = if (!isAudio) {
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
        playerSubtitles = playerSubtitles,
        nextEpisode = nextEpisode,
        onNextEpisode = onNextEpisode,
        upNextCard = upNextEnd.counting(upNext, pictureInPicture.value),
        upNextRemainingMillis = { upNextEnd.remainingMillis },
        onPlayUpNext = upNextEnd::play,
        onCancelUpNext = { upNextEnd.cancelled = true },
        neighbours = neighbours,
        onPlay = onPlay,
        enterPictureInPicture = enterPictureInPicture,
        pictureInPictureSeeks = pictureInPictureSeeks,
        castButton = castButton,
        stream = source.streamSwitch(mediaType, offline, player, currentOnOriginalRejected, onOriginalChosen),
    )
}

private fun PlaybackSource?.streamSwitch(
    mediaType: PlaybackMediaType,
    offline: Boolean,
    player: Media3Player,
    onOriginalRejected: State<(PlaybackFallbackReason, Long) -> Boolean>,
    onOriginalChosen: ((Long) -> Unit)?,
): PlaybackStreamSwitch? {
    val position = { player.currentPosition.coerceAtLeast(0L) }
    return when {
        this?.kind == PlaybackSourceKind.ORIGINAL && mediaType == PlaybackMediaType.VIDEO && !offline ->
            PlaybackStreamSwitch(PlaybackStream.Original) {
                onOriginalRejected.value(PlaybackFallbackReason.UserSwitched, position())
            }
        onOriginalChosen != null -> PlaybackStreamSwitch(PlaybackStream.Converted) { onOriginalChosen(position()) }
        else -> null
    }
}

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

    var activeFileId by mutableStateOf(player.sessionFileId(sessionHandled))
    var optionsInitialized by mutableStateOf(false)
    var cues by mutableStateOf(player.currentCues.cues)

    val cueHistory = DelayedSubtitleCues()
    var trackSelection = player.trackSelectionParameters
    var videoSize by mutableStateOf(player.videoSize)
    var playbackState by mutableIntStateOf(player.playbackState)
    var keepScreenOn by mutableStateOf(keepScreenOn)
    var stalled by mutableStateOf(false)
    var playerWantsToPlay by mutableStateOf(player.playWhenReady)
    var retainedPlayIntent by mutableStateOf(resumeAfterLifecyclePause)
    var failurePositionMillis by mutableStateOf<Long?>(null)
    var endedReported by mutableStateOf(false)
    var firstFrameRendered by mutableStateOf(false)
    var seekWindow by mutableStateOf(player.currentSeekWindow())
    var nextSeekRequestId by mutableLongStateOf(0L)

    var videoFrame by mutableStateOf<Rect?>(null)

    val recovery = PlaybackTrackRecovery(
        sidecarUris = { player.currentMediaItem?.sidecarSubtitleUris().orEmpty() },
        manifestAudioUris = player::manifestAudioUris,
        manifestSubtitleUris = player::manifestSubtitleUris,
    )

    val notices = Channel<Int>(Channel.BUFFERED)

    fun audioSwitching(preferences: RetainedPlayerPreferences) {
        recovery.audioSwitching(
            PlaybackAudioSwitch(player.trackSelectionParameters, preferences.audioSelection),
            SystemClock.elapsedRealtime(),
        )
    }

    fun dropSubtitles(step: PlaybackTrackRecoveryStep.DropSubtitles, preferences: RetainedPlayerPreferences) {
        preferences.subtitleSelection = SubtitleSelection.Off
        cueHistory.clear()
        player.continueWithout(step)
        if (step.notify) notices.trySend(R.string.mobile_playback_subtitles_failed)
    }

    fun recoverTrack(error: PlaybackException, preferences: RetainedPlayerPreferences): Boolean =
        when (val step = recovery.recover(error.toTrackFailure(), SystemClock.elapsedRealtime())) {
            is PlaybackTrackRecoveryStep.DropSubtitles -> {
                dropSubtitles(step, preferences)
                true
            }
            is PlaybackTrackRecoveryStep.RestoreAudio -> {
                preferences.audioSelection = step.switch.selection
                player.trackSelectionParameters = player.trackSelectionParameters.withAudioOf(step.switch.parameters)
                player.prepare()
                notices.trySend(R.string.mobile_playback_audio_failed)
                true
            }
            null -> false
        }

    fun refreshKeepScreenOn(isAudio: Boolean) {
        keepScreenOn = !stalled && player.keepsVideoScreenOn(isAudio)
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun resetForMediaItem() {
        firstFrameRendered = false
        failurePositionMillis = null
        endedReported = false
        cues = CueGroup.EMPTY_TIME_ZERO.cues
        cueHistory.clear()
        recovery.reset()
    }
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
        if (!playerState.ownsPlayer && replacingFileId != fileId && player.mediaItemCount > 0) {
            preferences.playbackSpeed = player.playbackParameters.speed
        }
        player.setPlaybackSpeed(preferences.playbackSpeed)
        player.trackSelectionParameters = player.trackSelectionParameters.withAudioSelection(
            preferences.audioSelection,
            emptyList(),
        )
        playerState.optionsInitialized = true
        playerState.resetForMediaItem()
        player.setMediaItem(playerFactory.reportableItem(preparedPlayback.mediaItem, useStartFrom), replacementPosition)
        player.prepare()
        playerState.seekWindow = player.currentSeekWindow()
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
    val context = LocalContext.current
    val player = playerState.player
    LaunchedEffect(player, playerState.activeFileId, subtitleStartupPolicy, retainedSubtitleSelection) {
        val policy = subtitleStartupPolicy ?: return@LaunchedEffect
        if (playerState.activeFileId == fileId && (retainedSubtitleSelection == null || !policy.showSubtitles)) {
            val current = player.trackSelectionParameters
            val parameters =
                if (policy.showSubtitles && policy.autoSelectSubtitles) {
                    current.withSubtitleSelection(
                        SubtitleSelection.Automatic,
                        player.currentTracks.playbackSubtitleTracks(),
                        playerState.defaultTrackSelection,
                        context.captionLanguages(),
                        player.inFileSubtitleLanguage(),
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
    currentOnReachedEnd: State<() -> Unit>,
    currentOnPlayerFailure: State<(PlaybackFailure, Long) -> Unit>,
    currentOnOriginalRejected: State<(PlaybackFallbackReason, Long) -> Boolean>,
    currentOnFirstFrame: State<() -> Unit>,
    currentOnPlaybackRetained: State<(RetainedPlayback) -> Unit>,
    currentOnPositionChanged: State<(Long) -> Unit>,
    pictureInPicture: State<Boolean>,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val player = playerState.player
    DisposableEffect(player) {
        fun lifecycleState() = lifecycle.currentState.withPictureInPicture(
            pictureInPicture.value || activity?.isInMultiWindowMode == true,
        )
        fun resolveRetainedSubtitleSelection(tracks: List<PlaybackSubtitleTrack>) {
            val parameters = player.trackSelectionParameters.withSubtitleTracks(
                retained = currentPreferences.value.subtitleSelection,
                startupPolicy = currentSubtitleStartupPolicy.value,
                tracks = tracks,
                textDefaults = playerState.defaultTrackSelection,
                captionLanguages = context.captionLanguages(),
                inFileLanguage = player.inFileSubtitleLanguage(),
            )
            if (parameters != player.trackSelectionParameters) {
                player.trackSelectionParameters = parameters
            }
        }
        val listener =
            object : Media3Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (playerState.playerReleased) return
                    if (playerState.recoverTrack(error, currentPreferences.value)) return
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
                    val rejection = error.originalRejection().takeUnless { playerState.firstFrameRendered }
                    if (rejection == null || !currentOnOriginalRejected.value(rejection, errorPositionMillis)) {
                        currentOnPlayerFailure.value(
                            error.toPlaybackFailure(),
                            player.currentPosition,
                        )
                    }
                }

                override fun onRenderedFirstFrame() {
                    playerState.firstFrameRendered = true
                    currentOnFirstFrame.value()
                }

                override fun onCues(cueGroup: CueGroup) {
                    playerState.cues = cueGroup.cues
                    playerState.cueHistory.record(player.currentPosition, cueGroup.cues)
                }

                override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                    if (!parameters.sameSubtitleChoice(playerState.trackSelection)) playerState.cueHistory.clear()
                    playerState.trackSelection = parameters
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Media3Player.PositionInfo,
                    newPosition: Media3Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Media3Player.DISCONTINUITY_REASON_SEEK) playerState.cueHistory.clear()
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    playerState.videoSize = size
                }

                override fun onTracksChanged(tracks: Tracks) {
                    val rejection = directPlayVerdict(tracks).takeUnless { isAudio || playerState.playerReleased }
                    if (rejection != null &&
                        currentOnOriginalRejected.value(rejection, player.currentPosition.coerceAtLeast(0L))
                    ) {
                        return
                    }
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
                    playerState.refreshKeepScreenOn(isAudio)
                    if (newPlaybackState == Media3Player.STATE_ENDED) {
                        if (!playerState.endedReported) {
                            playerState.endedReported = true
                            currentOnReachedEnd.value()
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
                    playerState.refreshKeepScreenOn(isAudio)
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    playerState.playerWantsToPlay = playWhenReady
                    playerState.refreshKeepScreenOn(isAudio)
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
        val loads = playerState.trackLoadListener(currentPreferences)
        (player as? ExoPlayer)?.addAnalyticsListener(loads)
        resolveRetainedSubtitleSelection(player.currentTracks.playbackSubtitleTracks())
        onDispose {
            player.removeListener(listener)
            (player as? ExoPlayer)?.removeAnalyticsListener(loads)
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

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun ReadyPlayerState.trackLoadListener(preferences: State<RetainedPlayerPreferences>): AnalyticsListener =
    playbackTrackLoadListener(
        player = player,
        isReleased = { playerReleased },
        recovery = recovery,
        onDropSubtitles = { dropSubtitles(it, preferences.value) },
    )

@Composable
private fun SubtitlesUnavailableNotice(playerState: ReadyPlayerState, video: PlaybackSource?) {
    val unavailable = video?.subtitles is PlaybackSubtitles.Unavailable
    LaunchedEffect(playerState, video, unavailable) {
        if (unavailable) playerState.notices.send(R.string.mobile_playback_subtitles_failed)
    }
}

@Composable
private fun MobilePlayerNotices(notices: Channel<Int>, shown: Boolean, modifier: Modifier) {
    val resources = LocalResources.current
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(notices, shown) {
        if (shown) notices.receiveAsFlow().collectLatest { hostState.showSnackbar(resources.getString(it)) }
    }
    if (shown) MobileSnackbarHost(hostState, modifier)
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
    playerSubtitles: PlaybackSubtitles,
    nextEpisode: PlaybackTarget?,
    onNextEpisode: () -> Unit,
    upNextCard: PlaybackUpNext?,
    upNextRemainingMillis: () -> Long,
    onPlayUpNext: (PlaybackTarget) -> Unit,
    onCancelUpNext: () -> Unit,
    neighbours: PlaybackNeighbours,
    onPlay: (PlaybackTarget) -> Unit,
    enterPictureInPicture: (() -> Unit)?,
    pictureInPictureSeeks: SeekRelay,
    castButton: @Composable (Modifier) -> Unit,
    stream: PlaybackStreamSwitch?,
) {
    val player = playerState.player
    val stallWatch = rememberPlaybackStallWatch(player)
    val stall by stallWatch.stall.collectAsState()
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
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(keyFocus) { keyFocus.requestFocus() }

    val options = rememberPlayerOptionsSnapshot(player)
    val onAudioSelectionChanged = { selection: AudioSelection ->
        playerState.audioSwitching(preferences)
        preferences.audioSelection = selection
    }
    val appearance = rememberSubtitleAppearanceStore()
    val sheetState = rememberSaveable(fileId) { mutableStateOf<MobileVideoSheet?>(null) }
    var sheet by sheetState
    SheetClosingEffect(sheetState, pictureInPicture, subtitlesHidden = subtitleStartupPolicy?.showSubtitles == false)
    SheetFocusEffect(sheet != null, controlsMenuOpen, keyFocus)
    SubtitleOffsetEffect(player, preferences.subtitleOffsetMillis)
    val optionModifier = Modifier.observeControlNavigation(onPointerNavigation, onKeyboardNavigation)
    var contentInWindow by remember { mutableStateOf(Rect.Zero) }
    val fold = tabletopFoldIn(contentInWindow, enabled = !pictureInPicture)
    val chromeLayout = remember(fold, contentInWindow.size) { MobilePlayerChromeLayout() }
    val stalled = stall.coversPlayer(pictureInPicture)
    MobileStallEffect(
        stalled = stalled,
        playerFocus = keyFocus,
        onStalled = {
            sheet = null
            playerState.stalled = true
            playerState.refreshKeepScreenOn(isAudio)
        },
        onRecovered = {
            playerState.stalled = false
            playerState.refreshKeepScreenOn(isAudio)
        },
    )
    val stageModifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned { contentInWindow = it.boundsInWindow() }
        .onPlayerKeyEvent(enabled = !stalled) { event ->
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
        }
    if (isAudio) {
        Box(stageModifier) {
            if (!stalled) {
                MobileNowPlayingScreen(
                    player = player,
                    title = title,
                    neighbours = neighbours,
                    seekEnabled = playerState.seekWindow.available,
                    onSeek = ::seek,
                    onScrub = { pendingSeek.value = null },
                    onPlay = onPlay,
                    onAudioSelectionChanged = onAudioSelectionChanged,
                    castButton = castButton,
                    modifier = Modifier.zIndex(2f).observeControlNavigation(onPointerNavigation, onKeyboardNavigation),
                )
                MobileSeekFeedbackLayer(pendingSeek.value, Modifier.zIndex(3f))
            }
            MobilePlayerNotices(
                playerState.notices,
                shown = !pictureInPicture,
                Modifier.align(Alignment.BottomCenter).zIndex(3.5f),
            )
            MobilePlaybackStallLayer(
                stall = stall,
                pictureInPicture = pictureInPicture,
                title = title,
                video = false,
                controlsShown = controlsVisible.value,
                sheetOpen = sheet != null,
                fold = null,
                onRetry = stallWatch::retry,
                onBack = onBack,
                modifier = Modifier.zIndex(4f),
            )
        }
    } else {
        val chromeLayerShown = stall.allowsChrome(pictureInPicture) && sheet == null
        val toggleControls = {
            onPointerNavigation()
            controlsVisible.value = controlsVisibleAfterTap(
                controlsVisible = controlsVisible.value,
                playbackState = playerState.playbackState,
                touchExplorationEnabled = touchExplorationEnabled,
            )
        }
        MobileVideoStage(
            modifier = stageModifier,
            fold = fold,
            chromeLayout = chromeLayout,
            chromeLayerShown = chromeLayerShown,
            chromeVisible = chromeLayerShown && controlsVisible.value,
            pictureInPicture = pictureInPicture,
            buffering = stall == PlaybackStall.Buffering,
            contentFrame = {
                MobileVideoContentFrame(playerState, pictureInPicture)
            },
            gestures = {
                MobileVideoGestures(
                    playerState = playerState,
                    fileId = fileId,
                    touchExplorationEnabled = touchExplorationEnabled,
                    controlsVisible = controlsVisible,
                    onSeek = ::seek,
                    onPointerNavigation = onPointerNavigation,
                    onToggleControls = toggleControls,
                )
            },
            lowerFoldTap = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(player, touchExplorationEnabled) {
                            detectVideoTapGestures(onDoubleTap = { toggleControls() }, onTap = { toggleControls() })
                        },
                )
            },
            chrome = {
                MobilePlayerChrome(
                    player = player,
                    title = title,
                    visible = true,
                    seekEnabled = playerState.seekWindow.available,
                    onSeek = ::seek,
                    onScrub = { pendingSeek.value = null },
                    onBack = onBack,
                    topActions = {
                        castButton(optionModifier)
                        MobilePictureInPictureButton(enterPictureInPicture, optionModifier)
                    },
                    settings = {
                        MobileVideoChips(
                            options = options,
                            playerSubtitles = playerSubtitles,
                            subtitleStartupPolicy = subtitleStartupPolicy,
                            stream = stream?.current,
                            onSheet = { sheet = it },
                            modifier = optionModifier,
                        )
                    },
                    trailingChip = { MobileNextEpisodeChip(nextEpisode, onNextEpisode, optionModifier) },
                    chromeLayout = chromeLayout,
                    fold = fold,
                )
            },
            upNext = {
                MobileUpNextCard(
                    upNext = upNextCard,
                    remainingMillis = upNextRemainingMillis,
                    onPlayNow = onPlayUpNext,
                    onCancel = onCancelUpNext,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(end = 24.dp)
                        .then(optionModifier),
                )
            },
            sheets = {
                MobileVideoSheets(
                    sheet = sheet,
                    onSheet = { sheet = it },
                    player = player,
                    options = options,
                    defaultTrackSelection = playerState.defaultTrackSelection,
                    playerSubtitles = playerSubtitles,
                    appearance = appearance,
                    preferences = preferences,
                    onSubtitleSelectionChanged = onSubtitleSelectionChanged,
                    onAudioSelectionChanged = onAudioSelectionChanged,
                    stream = stream,
                    modifier = Modifier,
                    panelModifier = optionModifier,
                )
            },
            seekFeedback = { MobileSeekFeedbackLayer(pendingSeek.value) },
            subtitles = { frame, clearance ->
                MobileSubtitleLayer(
                    playerState = playerState,
                    frame = frame,
                    textScale = appearance.textSize.scale,
                    besideSheet = sheet != null,
                    clearance = clearance,
                    delayMillis = preferences.subtitleOffsetMillis,
                )
            },
            pipProgress = { MobilePictureInPictureProgress(player, Modifier) },
            notices = {
                MobilePlayerNotices(
                    playerState.notices,
                    shown = !pictureInPicture,
                    Modifier,
                )
            },
            stallLayer = {
                MobilePlaybackStallLayer(
                    stall = stall,
                    pictureInPicture = pictureInPicture,
                    title = title,
                    video = true,
                    controlsShown = controlsVisible.value,
                    sheetOpen = sheet != null,
                    fold = null,
                    onRetry = stallWatch::retry,
                    onBack = onBack,
                )
            },
        )
    }
}

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

@Composable
private fun SheetFocusEffect(open: Boolean, controlsMenuOpen: MutableState<Boolean>, keyFocus: FocusRequester) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        controlsMenuOpen.value = open
        if (!open && wasOpen) keyFocus.requestFocus()
        wasOpen = open
    }
}

@Composable
private fun MobileSeekFeedbackLayer(request: PendingSeek?, modifier: Modifier = Modifier) {
    if (request == null) return
    Box(modifier.fillMaxSize()) {
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
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 32.dp, vertical = 88.dp),
            )
        }
    }
}

private class SeekRelay {
    var seek: (SeekDirection) -> Unit = {}
}

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
private fun MobileVideoContentFrame(
    playerState: ReadyPlayerState,
    pictureInPicture: Boolean,
) {
    val currentPictureInPicture by rememberUpdatedState(pictureInPicture)
    ContentFrame(
        player = playerState.player,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned {
                if (!currentPictureInPicture) playerState.videoFrame = it.boundsInWindow()
            },
        surfaceType = playbackSurfaceType(Build.VERSION.SDK_INT, Build.HARDWARE),
    )
}

@UnstableApi
@Composable
private fun MobileVideoGestures(
    playerState: ReadyPlayerState,
    fileId: Long,
    touchExplorationEnabled: Boolean,
    controlsVisible: MutableState<Boolean>,
    onSeek: (SeekDirection) -> Unit,
    onPointerNavigation: () -> Unit,
    onToggleControls: () -> Unit,
) {
    val player = playerState.player
    Box(
        Modifier
            .fillMaxSize()
            .testTag(MOBILE_PLAYER_GESTURE_TAG),
    ) {
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
                            onTap = { onToggleControls() },
                        )
                    },
            )
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileSubtitleLayer(
    playerState: ReadyPlayerState,
    frame: DpSize,
    textScale: Float,
    besideSheet: Boolean,
    clearance: Dp,
    delayMillis: Long,
) {
    val aspectRatio = playerState.videoSize.displayAspectRatioOrNull()
    val bottomPadding by animateFloatAsState(
        subtitleBottomPadding(frame, aspectRatio, clearance),
        label = "subtitle-clearance",
    )
    val sheetWidth = if (besideSheet) MOBILE_PLAYER_SIDE_SHEET_WIDTH.coerceAtMost(frame.width) else 0.dp
    val area = subtitleArea(frame, aspectRatio, sheetWidth)
    if (sheetWidth > 0.dp && area == null) return
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

@Composable
private fun MobileSessionPlayerHost(
    mediaType: PlaybackMediaType,
    playerFactory: MobilePlayerFactory,
    content: @Composable (sessionPlayer: Media3Player?) -> Unit,
) {
    val context = LocalContext.current
    if (mediaType != PlaybackMediaType.AUDIO) {
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

internal fun Media3Player.activeSessionFileId(): Long? =
    if (playbackState == Media3Player.STATE_IDLE || deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
        null
    } else {
        currentMediaItem?.mediaId?.toLongOrNull()
    }

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

