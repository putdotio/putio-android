package io.putdotio.android.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeMeasureScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.indicators.ProgressIndicator
import androidx.media3.common.util.Util
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.MobileTooltip
import io.putdotio.android.R
import io.putdotio.android.design.putioMono
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.MobileIconButton

internal class MobilePlayerChromeLayout {
    var footerHeightPx by mutableIntStateOf(0)
    var fitsFold by mutableStateOf(true)
}

private enum class VideoStageSlot {
    Chrome,
    ContentFrame,
    Gestures,
    LowerFoldTap,
    Subtitles,
    PipProgress,
    SeekFeedback,
    UpNext,
    Sheets,
    Notices,
    StallLayer,
}

private class VideoStageMetrics(
    val shownFooterPx: Int,
    val effectiveFold: TabletopFold?,
    val videoHeightPx: Int,
    val videoConstraints: Constraints,
    val subtitleClearance: Dp,
    val frameSize: DpSize,
)

private fun SubcomposeMeasureScope.measureSlot(
    slot: VideoStageSlot,
    constraints: Constraints,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
): List<Placeable> = if (enabled) subcompose(slot, content).map { it.measure(constraints) } else emptyList()

private fun SubcomposeMeasureScope.stageMetrics(
    constraints: Constraints,
    fold: TabletopFold?,
    chromeLayout: MobilePlayerChromeLayout,
    chromeVisible: Boolean,
    pictureInPicture: Boolean,
): VideoStageMetrics {
    val shownFooterPx = if (chromeVisible) chromeLayout.footerHeightPx else 0
    val effectiveFold = fold?.takeIf { chromeLayout.fitsFold }
    val videoHeightPx = effectiveFold?.top?.roundToPx()?.coerceIn(0, constraints.maxHeight)
        ?: constraints.maxHeight
    return VideoStageMetrics(
        shownFooterPx = shownFooterPx,
        effectiveFold = effectiveFold,
        videoHeightPx = videoHeightPx,
        videoConstraints = constraints.copy(minHeight = videoHeightPx, maxHeight = videoHeightPx),
        subtitleClearance = if (effectiveFold == null && !pictureInPicture) shownFooterPx.toDp() else 0.dp,
        frameSize = DpSize(constraints.maxWidth.toDp(), videoHeightPx.toDp()),
    )
}

@Composable
internal fun MobileVideoStage(
    modifier: Modifier,
    fold: TabletopFold?,
    chromeLayout: MobilePlayerChromeLayout,
    chromeLayerShown: Boolean,
    chromeVisible: Boolean,
    pictureInPicture: Boolean,
    buffering: Boolean,
    contentFrame: @Composable () -> Unit,
    gestures: @Composable () -> Unit,
    lowerFoldTap: @Composable () -> Unit,
    chrome: @Composable () -> Unit,
    upNext: @Composable () -> Unit,
    sheets: @Composable () -> Unit,
    seekFeedback: @Composable () -> Unit,
    subtitles: @Composable (DpSize, Dp) -> Unit,
    pipProgress: @Composable () -> Unit,
    notices: @Composable () -> Unit,
    stallLayer: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val chromePlaceables = measureSlot(VideoStageSlot.Chrome, constraints, chromeVisible, chrome)
        val metrics = stageMetrics(constraints, fold, chromeLayout, chromeVisible, pictureInPicture)
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val upNextGapPx = UP_NEXT_BOTTOM_GAP.roundToPx()
        val lowerHeight = (constraints.maxHeight - metrics.videoHeightPx).coerceAtLeast(0)
        val lowerConstraints = constraints.copy(minHeight = lowerHeight, maxHeight = lowerHeight)
        val maxUpNextHeight = (constraints.maxHeight - metrics.shownFooterPx - upNextGapPx).coerceAtLeast(0)
        val noticeMaxHeight = (constraints.maxHeight - metrics.shownFooterPx).coerceAtLeast(0)

        val contentPlaceables = measureSlot(
            VideoStageSlot.ContentFrame,
            metrics.videoConstraints,
            content = contentFrame,
        )
        val gesturePlaceables = measureSlot(VideoStageSlot.Gestures, metrics.videoConstraints, content = gestures)
        val lowerFoldPlaceables = measureSlot(
            VideoStageSlot.LowerFoldTap,
            lowerConstraints,
            metrics.effectiveFold != null,
            lowerFoldTap,
        )
        val upNextPlaceables = measureSlot(
            VideoStageSlot.UpNext,
            loose.copy(maxHeight = maxUpNextHeight),
            chromeLayerShown,
            upNext,
        )
        val sheetPlaceables = measureSlot(VideoStageSlot.Sheets, constraints, !pictureInPicture, sheets)
        val seekPlaceables = measureSlot(
            VideoStageSlot.SeekFeedback,
            metrics.videoConstraints,
            chromeLayerShown,
            seekFeedback,
        )
        val subtitlePlaceables = measureSlot(VideoStageSlot.Subtitles, metrics.videoConstraints) {
            subtitles(metrics.frameSize, metrics.subtitleClearance)
        }
        val pipPlaceables = measureSlot(VideoStageSlot.PipProgress, loose, pictureInPicture, pipProgress)
        val noticePlaceables = measureSlot(
            VideoStageSlot.Notices,
            loose.copy(maxHeight = noticeMaxHeight),
            content = notices,
        )
        val stallConstraints = if (buffering) metrics.videoConstraints else constraints
        val stallPlaceables = measureSlot(VideoStageSlot.StallLayer, stallConstraints, content = stallLayer)

        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.placeRelative(0, 0, zIndex = 0f) }
            lowerFoldPlaceables.forEach { it.placeRelative(0, metrics.videoHeightPx, zIndex = 0.5f) }
            gesturePlaceables.forEach { it.placeRelative(0, 0, zIndex = 0.5f) }
            chromePlaceables.forEach { it.placeRelative(0, 0, zIndex = 2f) }
            upNextPlaceables.forEach {
                val y = (constraints.maxHeight - it.height - metrics.shownFooterPx - upNextGapPx)
                    .coerceAtLeast(0)
                it.placeRelative(constraints.maxWidth - it.width, y, zIndex = 2.25f)
            }
            sheetPlaceables.forEach { it.placeRelative(0, 0, zIndex = 2.5f) }
            subtitlePlaceables.forEach { it.placeRelative(0, 0, zIndex = 3f) }
            seekPlaceables.forEach { it.placeRelative(0, 0, zIndex = 3f) }
            pipPlaceables.forEach { it.placeRelative(0, constraints.maxHeight - it.height, zIndex = 3f) }
            noticePlaceables.forEach {
                val y = (constraints.maxHeight - it.height - metrics.shownFooterPx).coerceAtLeast(0)
                it.placeRelative((constraints.maxWidth - it.width) / 2, y, zIndex = 3.5f)
            }
            stallPlaceables.forEach { it.placeRelative(0, 0, zIndex = 4f) }
        }
    }
}

private val UP_NEXT_BOTTOM_GAP = 8.dp

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobilePlayerChrome(
    player: Player,
    title: String,
    visible: Boolean,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    onScrub: () -> Unit,
    settings: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topActions: @Composable RowScope.() -> Unit = {},
    trailingChip: @Composable () -> Unit = {},
    chromeLayout: MobilePlayerChromeLayout? = null,
    fold: TabletopFold? = null,
    onFoldFits: (Boolean) -> Unit = {},
) {
    if (visible) {
        MobileVideoLayout(
            player, title, seekEnabled, onSeek, onScrub, settings, modifier, onBack, topActions, trailingChip,
            chromeLayout, fold, onFoldFits,
        )
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileVideoLayout(
    player: Player,
    title: String,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    onScrub: () -> Unit,
    settings: @Composable () -> Unit,
    modifier: Modifier,
    onBack: () -> Unit,
    topActions: @Composable RowScope.() -> Unit,
    trailingChip: @Composable () -> Unit,
    chromeLayout: MobilePlayerChromeLayout?,
    fold: TabletopFold?,
    onFoldFits: (Boolean) -> Unit,
) {
    val scrim = MaterialTheme.colorScheme.scrim
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safeDrawing = WindowInsets.safeDrawing
    val sidePadding = with(density) {
        maxOf(safeDrawing.getLeft(this, direction), safeDrawing.getRight(this, direction)).toDp() + 24.dp
    }
    BoxWithConstraints(
        modifier.fillMaxSize().shareVideoPointerInputWithSiblings().background(videoShade(scrim)),
    ) {
        val viewportHeight = constraints.maxHeight
        val scrollState = rememberScrollState()
        Layout(
            modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).heightIn(min = maxHeight),
            content = {
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Top))
                        .padding(horizontal = sidePadding, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MobileIconButton(stringResource(R.string.mobile_action_back), R.drawable.ic_ph_arrow_left, onBack)
                    MobilePlayerTitle(title, Modifier.weight(1f))
                    topActions()
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                    MobileVideoTransport(player, seekEnabled, onSeek)
                }
                MobileVideoFooter(
                    modifier = Modifier.fillMaxWidth()
                        .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(start = sidePadding, end = sidePadding, bottom = 8.dp),
                    times = { MobileVideoTimes(player) },
                    timeline = {
                        MobileProgressSlider(
                            player,
                            onScrub,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = VIDEO_TRACK_ALPHA),
                            thumbSize = VIDEO_THUMB_SIZE,
                        )
                    },
                    chips = {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            settings()
                            Spacer(Modifier.weight(1f))
                            trailingChip()
                        }
                    },
                )
            },
        ) { measurables, limits ->
            val children = measurables.map {
                it.measure(limits.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            }
            val (header, transport, footer) = children
            val height = limits.constrainHeight(children.sumOf { it.height })
            val controlsTop = fold
                ?.takeIf { header.height <= it.top.roundToPx() }
                ?.bottom?.roundToPx()
                ?.takeIf { height <= viewportHeight && it + transport.height + footer.height <= height }
            chromeLayout?.footerHeightPx = footer.height
            chromeLayout?.fitsFold = fold == null || controlsTop != null
            if (fold != null) onFoldFits(controlsTop != null)
            layout(limits.maxWidth, height) {
                header.placeRelative(0, 0)
                transport.placeRelative(
                    0,
                    if (controlsTop != null) {
                        controlsTop + (height - footer.height - controlsTop - transport.height) / 2
                    } else {
                        ((height - transport.height) / 2)
                            .coerceIn(header.height, height - footer.height - transport.height)
                    },
                )
                footer.placeRelative(0, height - footer.height)
            }
        }
    }
}

@Composable
internal fun MobilePlayerTitle(title: String, modifier: Modifier = Modifier) {
    MobileMiddleCutText(
        text = title,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        modifier = modifier,
    )
}

internal fun videoShade(scrim: Color): Brush =
    Brush.verticalGradient(
        0f to scrim.copy(alpha = SHADE_TOP_ALPHA),
        SHADE_CLEAR_FROM to scrim.copy(alpha = SHADE_CLEAR_ALPHA),
        SHADE_CLEAR_TO to scrim.copy(alpha = SHADE_CLEAR_ALPHA),
        1f to scrim.copy(alpha = SHADE_BOTTOM_ALPHA),
    )

internal val MOBILE_TRANSPORT_RING = 80.dp
private const val SHADE_TOP_ALPHA = 0.6f
private const val SHADE_CLEAR_FROM = 0.3f
private const val SHADE_CLEAR_TO = 0.6f
private const val SHADE_CLEAR_ALPHA = 0.05f
private const val SHADE_BOTTOM_ALPHA = 0.75f

@Composable
private fun MobileVideoFooter(
    modifier: Modifier,
    times: @Composable () -> Unit,
    timeline: @Composable () -> Unit,
    chips: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = { times(); timeline(); chips() }) { measurables, limits ->
        val loose = limits.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val (timesPlaceable, timelinePlaceable, chipsPlaceable) = measurables.map { it.measure(loose) }
        val timelineTop = timesPlaceable.height - TIMES_OVER_TIMELINE.roundToPx().coerceAtMost(timesPlaceable.height)
        val chipsTop = timelineTop + timelinePlaceable.height
        layout(limits.maxWidth, chipsTop + chipsPlaceable.height) {
            timesPlaceable.placeRelative(0, 0)
            timelinePlaceable.placeRelative(0, timelineTop)
            chipsPlaceable.placeRelative(0, chipsTop)
        }
    }
}

private val TIMES_OVER_TIMELINE = 12.dp
private val VIDEO_THUMB_SIZE = 16.dp
private const val VIDEO_TRACK_ALPHA = 0.3f

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileVideoTimes(player: Player) {
    ProvideTextStyle(
        MaterialTheme.typography.bodySmall.putioMono().copy(color = MaterialTheme.colorScheme.onSurface),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MobileTimeText(player, PlayerTime.Position)
            MobileTimeText(player, PlayerTime.Remaining)
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileVideoTransport(
    player: Player,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
) {
    val playPause = rememberPlayPauseButtonState(player)
    val buffering = rememberBuffering(player)
    Row(
        horizontalArrangement = Arrangement.spacedBy(VIDEO_TRANSPORT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileSeekButton(SeekDirection.Backward, seekEnabled, { onSeek(SeekDirection.Backward) }, onVideo = true)
        Box(Modifier.size(MOBILE_TRANSPORT_RING), contentAlignment = Alignment.Center) {
            if (buffering) {
                val loading = stringResource(R.string.mobile_state_loading)
                CircularProgressIndicator(
                    modifier = Modifier.size(MOBILE_TRANSPORT_RING).semantics { contentDescription = loading },
                    strokeWidth = 2.dp,
                )
            }
            val circle = MaterialTheme.colorScheme.scrim.copy(alpha = VIDEO_PLAY_CIRCLE_ALPHA)
            val label = stringResource(
                if (playPause.showPlay) R.string.mobile_now_playing_play else R.string.mobile_now_playing_pause,
            )
            MobileTooltip(label) {
                FilledIconButton(
                    onClick = playPause::onClick,
                    enabled = playPause.isEnabled,
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = circle,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContainerColor = circle,
                    ),
                ) {
                    Icon(
                        painterResource(
                            if (playPause.showPlay) {
                                DesignR.drawable.ic_ph_play_fill
                            } else {
                                DesignR.drawable.ic_ph_pause_fill
                            },
                        ),
                        contentDescription = label,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
        MobileSeekButton(SeekDirection.Forward, seekEnabled, { onSeek(SeekDirection.Forward) }, onVideo = true)
    }
}

private val VIDEO_TRANSPORT_GAP = 52.dp
private const val VIDEO_PLAY_CIRCLE_ALPHA = 0.42f

@Composable
internal fun rememberBuffering(player: Player): Boolean {
    var buffering by remember(player) { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return buffering
}

internal enum class PlayerTime { Position, Duration, Remaining }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileTimeText(player: Player, time: PlayerTime) {
    val progress = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1_000)
    MobileTimeText(time, progress.currentPositionMs, progress.durationMs)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileTimeText(
    time: PlayerTime,
    positionMillis: Long,
    durationMillis: Long,
    modifier: Modifier = Modifier,
) {
    val text = when (time) {
        PlayerTime.Position -> Util.getStringForTime(positionMillis)
        PlayerTime.Duration -> Util.getStringForTime(durationMillis)
        PlayerTime.Remaining -> if (durationMillis == C.TIME_UNSET) {
            Util.getStringForTime(C.TIME_UNSET)
        } else {
            "-" + Util.getStringForTime((durationMillis - positionMillis).coerceAtLeast(0L))
        }
    }
    Text(text, modifier, style = LocalTextStyle.current.putioMono())
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileProgressSlider(
    player: Player,
    onScrub: () -> Unit = {},
    inactiveTrackColor: Color = MaterialTheme.colorScheme.outlineVariant,
    thumbSize: Dp = 12.dp,
) {
    var width by remember { mutableIntStateOf(0) }
    var dragging by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableFloatStateOf(0f) }
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            if (interaction is DragInteraction.Cancel || interaction is PressInteraction.Cancel) dragging = false
        }
    }
    val colors = SliderDefaults.colors(inactiveTrackColor = inactiveTrackColor)
    val label = stringResource(R.string.mobile_playback_position)
    ProgressIndicator(player, totalTickCount = width) {
        LaunchedEffect(changingProgressEnabled, durationMs) { dragging = false }
        val progress = if (dragging) position else currentPositionProgress
        val description = stringResource(
            R.string.mobile_playback_position_value,
            android.text.format.DateUtils.formatElapsedTime(progressToPosition(progress).coerceAtLeast(0) / 1_000),
            android.text.format.DateUtils.formatElapsedTime(durationMs.coerceAtLeast(0) / 1_000),
        )
        val sliderState = remember(player) { SliderState(progress) }
        sliderState.value = progress
        Slider(
            state = sliderState,
            onValueChange = {
                onScrub()
                dragging = true
                position = it
            },
            onValueChangeFinished = {
                if (dragging) updateCurrentPositionProgress(position)
                dragging = false
            },
            enabled = changingProgressEnabled,
            modifier = Modifier.fillMaxWidth().onSizeChanged { width = it.width }
                .onFocusChanged { if (!it.hasFocus) dragging = false }
                .testTag("mobile-player-timeline")
                .semantics {
                    contentDescription = label
                    stateDescription = description
                },
            colors = colors,
            interactionSource = interactions,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactions,
                    colors = colors,
                    enabled = changingProgressEnabled,
                    thumbSize = DpSize(thumbSize, thumbSize),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = colors,
                    modifier = Modifier.height(4.dp),
                    enabled = changingProgressEnabled,
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
        )
    }
}
