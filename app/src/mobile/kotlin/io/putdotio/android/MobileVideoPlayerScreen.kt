package io.putdotio.android

import android.os.Build
import android.os.Bundle
import android.view.accessibility.CaptioningManager
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
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
import androidx.media3.datasource.HttpDataSource
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
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val MOBILE_VIDEO_PLAYER_TAG = "mobile-video-player"
internal const val MOBILE_SUBTITLE_CUES_TAG = "mobile-subtitle-cues"
private const val MILLIS_PER_SECOND = 1_000.0
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileVideoPlayerScreen(
    state: PlaybackState,
    onRetry: () -> Unit,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preferences = rememberRetainedPlayerPreferences(state.target.fileId.value)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val content = state.content) {
            is PlaybackContent.Loading ->
                MobileLoadingState(stringResource(R.string.mobile_playback_loading))

            is PlaybackContent.Ready ->
                MobileReadyVideoPlayer(
                    source = content.source,
                    title = state.target.name,
                    startPositionMillis = state.resumePositionMillis ?: preferences.positionMillis,
                    resumeAfterLifecyclePause = preferences.resumeAfterLifecyclePause,
                    retainedTrackSelection = preferences.trackSelection,
                    onPlaybackRetained = preferences::retainPlayback,
                    onPositionChanged = preferences::retainPosition,
                    onTrackSelectionChanged = { preferences.trackSelection = it.toBundle() },
                    onPlayerFailure = { failure, positionMillis ->
                        onPlayerFailure(failure, positionMillis)
                    },
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
    trackSelection: Bundle? = null,
    positionMillis: Long? = null,
) {
    var resumeAfterLifecyclePause by mutableStateOf(resumeAfterLifecyclePause)
    var trackSelection by mutableStateOf(trackSelection)
    var positionMillis by mutableStateOf(positionMillis)

    fun retainPlayback(playback: RetainedPlayback) {
        positionMillis = playback.positionMillis
        resumeAfterLifecyclePause = playback.resumeAfterLifecyclePause
    }

    fun retainPosition(positionMillis: Long) {
        this.positionMillis = positionMillis.coerceAtLeast(0L)
    }
}

private val RetainedPlayerPreferencesSaver =
    Saver<RetainedPlayerPreferences, Bundle>(
        save = { preferences ->
            Bundle().apply {
                putBoolean("resumeAfterLifecyclePause", preferences.resumeAfterLifecyclePause)
                preferences.trackSelection?.let { putBundle("trackSelection", it) }
                preferences.positionMillis?.let { putLong("positionMillis", it) }
            }
        },
        restore = { saved ->
            RetainedPlayerPreferences(
                resumeAfterLifecyclePause = saved.getBoolean("resumeAfterLifecyclePause"),
                trackSelection = saved.getBundle("trackSelection"),
                positionMillis = saved.getLong("positionMillis").takeIf { saved.containsKey("positionMillis") },
            )
        },
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
    retainedTrackSelection: Bundle?,
    onPlaybackRetained: (RetainedPlayback) -> Unit,
    onPositionChanged: (Long) -> Unit,
    onTrackSelectionChanged: (TrackSelectionParameters) -> Unit,
    onPlayerFailure: (PlaybackFailure, Long) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val initialPlayback = remember(source, title, startPositionMillis) {
        source.preparePlayback(title, startPositionMillis)
    }
    var retainedPositionMillis by rememberSaveable(source.fileId) {
        mutableStateOf(initialPlayback.startPositionMillis)
    }
    val preparedPlayback = remember(source, title) {
        source.preparePlayback(title, retainedPositionMillis)
    }
    val currentOnPlayerFailure = rememberUpdatedState(onPlayerFailure)
    val currentOnPlaybackRetained = rememberUpdatedState(onPlaybackRetained)
    val currentOnPositionChanged = rememberUpdatedState(onPositionChanged)
    val player = remember(context, preparedPlayback, lifecycle) {
        val renderersFactory = DefaultRenderersFactory(context)
        if (requiresEmulatorCodecWorkaround(Build.VERSION.SDK_INT, Build.HARDWARE)) {
            // API 37's goldfish AVC codec can fail its memfd queue before decoding a frame.
            renderersFactory
                .setMediaCodecSelector(EmulatorMediaCodecSelector)
                .setEnableDecoderFallback(true)
        }
        ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(MobileVideoAudioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
            setMediaItem(preparedPlayback.mediaItem, preparedPlayback.startPositionMillis)
            trackSelectionParameters =
                restoreTrackSelection(
                    defaults = trackSelectionParameters,
                    retained = retainedTrackSelection,
                    systemCaptionsEnabled = context.systemCaptionsEnabled(),
                )
            prepare()
            playWhenReady =
                lifecycleAllowsAutoplay(lifecycle.currentState, resumeAfterLifecyclePause)
        }
    }
    var cues by remember(player) { mutableStateOf(player.currentCues.cues) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    var keepScreenOn by remember(player) { mutableStateOf(player.shouldKeepScreenOn()) }
    val hostView = LocalView.current

    DisposableEffect(hostView, keepScreenOn) {
        if (keepScreenOn) hostView.keepScreenOn = true
        onDispose {
            if (keepScreenOn) hostView.keepScreenOn = false
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        val retained = retainPlaybackOnPause(player.currentPosition, player.playWhenReady)
        retainedPositionMillis = retained.positionMillis
        onPlaybackRetained(retained)
        player.pause()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumeAfterLifecyclePause) player.play()
    }
    DisposableEffect(player) {
        val listener =
            object : Media3Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    currentOnPlaybackRetained.value(
                        retainPlaybackOnPause(player.currentPosition, player.playWhenReady),
                    )
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

                override fun onPlaybackStateChanged(playbackState: Int) {
                    keepScreenOn = player.shouldKeepScreenOn()
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    keepScreenOn = player.shouldKeepScreenOn()
                    retainPlaybackWhileActive(
                        lifecycleState = lifecycle.currentState,
                        positionMillis = player.currentPosition,
                        playWhenReady = playWhenReady,
                    )?.let(currentOnPlaybackRetained.value)
                }
            }
        player.addListener(listener)
        onDispose {
            retainedPositionMillis = player.currentPosition.coerceAtLeast(0L)
            retainPlaybackWhileActive(
                lifecycleState = lifecycle.currentState,
                positionMillis = retainedPositionMillis,
                playWhenReady = player.playWhenReady,
            )?.let(currentOnPlaybackRetained.value)
                ?: currentOnPositionChanged.value(retainedPositionMillis)
            onTrackSelectionChanged(player.trackSelectionParameters)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .testTag(MOBILE_VIDEO_PLAYER_TAG),
    ) {
        ContentFrame(
            player = player,
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center),
            surfaceType = playbackSurfaceType(Build.VERSION.SDK_INT, Build.HARDWARE),
        )
        MobileSubtitleCueOverlay(
            cues = cues,
            videoAspectRatio = videoSize.displayAspectRatioOrNull(),
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .zIndex(1f),
        )
        PlayerDefaults.TopControls(
            player = player,
            visible = true,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp),
        ) {
            if (source.hasSelectableSubtitles() && it != null) {
                MobileSubtitleControls(
                    player = it,
                    onTrackSelectionChanged = onTrackSelectionChanged,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        PlayerDefaults.CenterControls(
            player = player,
            visible = true,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .zIndex(2f),
        )
        PlayerDefaults.BottomControls(
            player = player,
            visible = true,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(2f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp),
        )
    }
}

@Composable
@UnstableApi
internal fun MobileSubtitleCueOverlay(
    cues: List<Cue>,
    modifier: Modifier = Modifier,
    videoAspectRatio: Float? = null,
) {
    if (cues.isEmpty()) return
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

private fun Modifier.fitInsideAspectRatio(aspectRatio: Float?): Modifier =
    if (aspectRatio == null || !aspectRatio.isFinite() || aspectRatio <= 0f) {
        fillMaxSize()
    } else {
        layout { measurable, constraints ->
            val fitted = fitInside(constraints.maxWidth, constraints.maxHeight, aspectRatio)
            val placeable =
                measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(fitted.width, fitted.height),
                )
            layout(fitted.width, fitted.height) { placeable.place(0, 0) }
        }
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
    playbackKeepsScreenOn(playWhenReady, playbackState)

internal fun playbackKeepsScreenOn(
    playWhenReady: Boolean,
    playbackState: Int,
): Boolean =
    playWhenReady && playbackState != Media3Player.STATE_IDLE && playbackState != Media3Player.STATE_ENDED

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

internal fun retainPlaybackWhileActive(
    lifecycleState: Lifecycle.State,
    positionMillis: Long,
    playWhenReady: Boolean,
): RetainedPlayback? =
    if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        retainPlaybackOnPause(positionMillis, playWhenReady)
    } else {
        null
    }

@Composable
private fun MobileSubtitleControls(
    player: Media3Player,
    onTrackSelectionChanged: (TrackSelectionParameters) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnTrackSelectionChanged = rememberUpdatedState(onTrackSelectionChanged)
    var tracks by remember(player) { mutableStateOf(player.mobileSubtitleTracks()) }
    var enabled by remember(player) {
        mutableStateOf(player.trackSelectionParameters.subtitlesEnabled(tracks))
    }
    var menuExpanded by remember(player) { mutableStateOf(false) }

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
                    currentOnTrackSelectionChanged.value(parameters)
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
                player.trackSelectionParameters =
                    player.trackSelectionParameters.withSubtitlesEnabled(subtitlesEnabled)
            },
        )
        if (tracks.size > 1) {
            Box {
                TextButton(onClick = { menuExpanded = true }) {
                    Text(stringResource(R.string.mobile_playback_choose_subtitles))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    tracks.forEach { track ->
                        MobileSubtitleTrackOption(
                            track = track,
                            onClick = {
                                enabled = true
                                player.trackSelectionParameters =
                                    player.trackSelectionParameters.withSubtitleTrack(track)
                                menuExpanded = false
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
            Modifier.semantics {
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

internal data class MobileSubtitleTrack(
    val group: TrackGroup,
    val trackIndex: Int,
    val label: String?,
    val selected: Boolean,
)

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
                        label = format.label ?: format.language,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
        }

internal fun TrackSelectionParameters.withSubtitleTrack(track: MobileSubtitleTrack): TrackSelectionParameters =
    buildUpon()
        .setSelectTextByDefault(true)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setOverrideForType(TrackSelectionOverride(track.group, track.trackIndex))
        .build()

internal fun TrackSelectionParameters.withSubtitlesEnabled(enabled: Boolean): TrackSelectionParameters =
    buildUpon()
        .setSelectTextByDefault(enabled)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
        .build()

internal fun TrackSelectionParameters.subtitlesEnabled(tracks: List<MobileSubtitleTrack>): Boolean =
    C.TRACK_TYPE_TEXT !in disabledTrackTypes &&
        (selectTextByDefault || tracks.any(MobileSubtitleTrack::selected))

internal fun restoreTrackSelection(
    defaults: TrackSelectionParameters,
    retained: Bundle?,
    systemCaptionsEnabled: Boolean,
): TrackSelectionParameters =
    retained?.let(TrackSelectionParameters::fromBundle)
        ?: if (systemCaptionsEnabled) {
            defaults
                .buildUpon()
                .setSelectTextByDefault(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        } else {
            defaults
        }

internal fun android.content.Context.systemCaptionsEnabled(): Boolean =
    (getSystemService(android.content.Context.CAPTIONING_SERVICE) as? CaptioningManager)?.isEnabled == true

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

internal data class PreparedPlayback(
    val mediaItem: MediaItem,
    val startPositionMillis: Long,
)

internal fun PlaybackSource.preparePlayback(
    title: String,
    resumePositionMillis: Long? = null,
): PreparedPlayback =
    PreparedPlayback(
        mediaItem = toMediaItem(title),
        startPositionMillis = resumePositionMillis ?: startFromSeconds.toPlaybackMillis(),
    )

internal fun PlaybackSource.toMediaItem(title: String): MediaItem {
    val subtitleConfigurations =
        (subtitles as? PlaybackSubtitles.Sidecar)
            ?.tracks
            .orEmpty()
            .mapNotNull { subtitle ->
                val mimeType = subtitle.toSubtitleMimeType() ?: return@mapNotNull null
                subtitle to mimeType
            }.map { (subtitle, mimeType) ->
                MediaItem.SubtitleConfiguration.Builder(subtitle.url.value.toUri())
                    .setId(subtitle.key)
                    .setLabel(subtitle.name)
                    .setLanguage(subtitle.languageCode)
                    .setMimeType(mimeType)
                    .setSelectionFlags(0)
                    .build()
            }

    return MediaItem.Builder()
        .setUri(url.value)
        .setMimeType(if (kind == PlaybackSourceKind.HLS) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .setSubtitleConfigurations(subtitleConfigurations)
        .build()
}

internal fun PlaybackSource.hasSelectableSubtitles(): Boolean =
    subtitles is PlaybackSubtitles.Embedded ||
        (subtitles as? PlaybackSubtitles.Sidecar)
            ?.tracks
            ?.any { it.toSubtitleMimeType() != null } == true

internal fun Throwable.toMediaRequestFailureOrNull(): PlaybackFailure? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    var networkFailure: HttpDataSource.HttpDataSourceException? = null
    while (current != null && visited.add(current)) {
        when (current) {
            is HttpDataSource.InvalidResponseCodeException ->
                if (current.responseCode == HTTP_UNAUTHORIZED || current.responseCode == HTTP_FORBIDDEN) {
                    return PlaybackFailure.MediaCredentialUnavailable(this)
                }

            is HttpDataSource.HttpDataSourceException ->
                if (current.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    current.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                ) {
                    networkFailure = current
                }
        }
        current = current.cause
    }
    return networkFailure?.let { PlaybackFailure.NetworkUnavailable(this) }
}

internal fun PlaybackException.toPlaybackFailure(): PlaybackFailure =
    toMediaRequestFailureOrNull() ?: PlaybackFailure.Unexpected(this)

private fun Double.toPlaybackMillis(): Long =
    (this * MILLIS_PER_SECOND)
        .coerceIn(0.0, Long.MAX_VALUE.toDouble())
        .roundToLong()

private fun String?.toSubtitleMimeType(): String? =
    when (this?.lowercase()) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ssa", "ass" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

private fun PlaybackSubtitle.toSubtitleMimeType(): String? =
    if (format == null) {
        url.encodedPath.substringAfterLast('.', missingDelimiterValue = "").toSubtitleMimeType()
    } else {
        format.toSubtitleMimeType()
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
