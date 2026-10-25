package io.putdotio.android.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.indicators.ProgressIndicator
import androidx.media3.common.util.Util
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import io.putdotio.android.R
import io.putdotio.android.design.putioMono
import io.putdotio.android.design.R as DesignR

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobilePlayerChrome(
    player: Player,
    title: String,
    isAudio: Boolean,
    visible: Boolean,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    onScrub: () -> Unit,
    settings: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topActions: @Composable RowScope.() -> Unit = {},
    trailingChip: @Composable () -> Unit = {},
    onFooterHeight: (Dp) -> Unit = {},
) {
    if (isAudio) {
        MobileAudioLayout(player, title, seekEnabled, onSeek, onScrub, settings, modifier)
    } else if (visible) {
        MobileVideoLayout(
            player, title, seekEnabled, onSeek, onScrub, settings, modifier, onBack, topActions, trailingChip,
            onFooterHeight,
        )
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileAudioLayout(
    player: Player,
    title: String,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    onScrub: () -> Unit,
    settings: @Composable () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 64.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.mobile_playback_now_playing),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            settings()
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            val artworkSize = if (maxHeight / LocalDensity.current.fontScale < 560.dp) 80.dp else 240.dp
            if (wide) {
                Row(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                ) {
                    MobileAudioArtwork(Modifier.weight(1f))
                    MobileAudioDetails(player, title, seekEnabled, onSeek, onScrub, Modifier.weight(1f))
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MobileAudioArtwork(Modifier.size(artworkSize))
                    MobileAudioDetails(player, title, seekEnabled, onSeek, onScrub, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun MobileAudioArtwork(modifier: Modifier) {
    Box(modifier.testTag(MOBILE_AUDIO_COVER_TAG), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(240.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(DesignR.drawable.ic_ph_file_audio_fill),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileAudioDetails(
    player: Player,
    title: String,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    onScrub: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.mobile_playback_audio_file),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MobilePlayerTimeline(player, onScrub)
        MobileTransport(player, seekEnabled, onSeek, Modifier.fillMaxWidth())
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
    onFooterHeight: (Dp) -> Unit,
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
        var contentHeight by remember { mutableIntStateOf(0) }
        val scrollState = rememberScrollState()
        val overflow = if (contentHeight > constraints.maxHeight) {
            Modifier.verticalScroll(scrollState)
        } else {
            Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
        }
        Layout(
            modifier = Modifier.fillMaxWidth().then(overflow).heightIn(min = maxHeight)
                .onSizeChanged { contentHeight = it.height },
            content = {
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Top))
                        .padding(horizontal = sidePadding, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_ph_arrow_left),
                            contentDescription = stringResource(R.string.mobile_action_back),
                        )
                    }
                    MobilePlayerTitle(title, Modifier.weight(1f))
                    topActions()
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                    MobileVideoTransport(player, seekEnabled, onSeek)
                }
                MobileVideoFooter(
                    modifier = Modifier.fillMaxWidth()
                        .onSizeChanged { onFooterHeight(with(density) { it.height.toDp() }) }
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
                        // Narrow or large-text windows wrap the chips instead of squeezing them.
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
            layout(limits.maxWidth, height) {
                header.placeRelative(0, 0)
                transport.placeRelative(
                    0,
                    ((height - transport.height) / 2)
                        .coerceIn(header.height, height - footer.height - transport.height),
                )
                footer.placeRelative(0, height - footer.height)
            }
        }
    }
}

/** The filename on one line, cut in the middle so its start and extension stay. */
@Composable
internal fun MobilePlayerTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = modifier,
    )
}

/** Dark behind the top bar, nearly clear across the picture, darkest behind the controls. */
internal fun videoShade(scrim: Color): Brush =
    Brush.verticalGradient(
        0f to scrim.copy(alpha = SHADE_TOP_ALPHA),
        SHADE_CLEAR_FROM to scrim.copy(alpha = SHADE_CLEAR_ALPHA),
        SHADE_CLEAR_TO to scrim.copy(alpha = SHADE_CLEAR_ALPHA),
        1f to scrim.copy(alpha = SHADE_BOTTOM_ALPHA),
    )

private const val SHADE_TOP_ALPHA = 0.6f
private const val SHADE_CLEAR_FROM = 0.3f
private const val SHADE_CLEAR_TO = 0.6f
private const val SHADE_CLEAR_ALPHA = 0.05f
private const val SHADE_BOTTOM_ALPHA = 0.75f

/**
 * The times, the scrubber, then the chips. The times line overlaps the top of the scrubber's 48dp
 * touch band, which nothing else there can take, so the yellow line sits close under the times.
 */
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

/** A 72dp translucent dark circle for play and pause, between 56dp circles for 10 seconds back and forward. */
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
        Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
            if (buffering) {
                val loading = stringResource(R.string.mobile_state_loading)
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp).semantics { contentDescription = loading },
                    strokeWidth = 2.dp,
                )
            }
            val circle = MaterialTheme.colorScheme.scrim.copy(alpha = VIDEO_PLAY_CIRCLE_ALPHA)
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
                        if (playPause.showPlay) DesignR.drawable.ic_ph_play_fill else DesignR.drawable.ic_ph_pause_fill,
                    ),
                    contentDescription = stringResource(
                        if (playPause.showPlay) R.string.mobile_now_playing_play else R.string.mobile_now_playing_pause,
                    ),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        MobileSeekButton(SeekDirection.Forward, seekEnabled, { onSeek(SeekDirection.Forward) }, onVideo = true)
    }
}

// The 80dp buffering ring holds the 72dp button, so the 56dp gap between circles loses 4dp each side.
private val VIDEO_TRANSPORT_GAP = 52.dp
private const val VIDEO_PLAY_CIRCLE_ALPHA = 0.42f

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileTransport(
    player: Player,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPause = rememberPlayPauseButtonState(player)
    val buffering = rememberBuffering(player)
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileSeekButton(SeekDirection.Backward, seekEnabled, { onSeek(SeekDirection.Backward) })
        Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
            if (buffering) {
                val loading = stringResource(R.string.mobile_state_loading)
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp).semantics { contentDescription = loading },
                    strokeWidth = 2.dp,
                )
            }
            FilledIconButton(
                onClick = playPause::onClick,
                enabled = playPause.isEnabled,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Icon(
                    painterResource(
                        if (playPause.showPlay) DesignR.drawable.ic_ph_play_fill else DesignR.drawable.ic_ph_pause_fill,
                    ),
                    contentDescription = stringResource(
                        if (playPause.showPlay) R.string.mobile_now_playing_play else R.string.mobile_now_playing_pause,
                    ),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        MobileSeekButton(SeekDirection.Forward, seekEnabled, { onSeek(SeekDirection.Forward) })
    }
}

@Composable
private fun rememberBuffering(player: Player): Boolean {
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

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobilePlayerTimeline(player: Player, onScrub: () -> Unit) {
    Column {
        MobileProgressSlider(player, onScrub)
        ProvideTextStyle(
            MaterialTheme.typography.labelMedium.putioMono().copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MobileTimeText(player, PlayerTime.Position)
                MobileTimeText(player, PlayerTime.Duration)
            }
        }
    }
}

private enum class PlayerTime { Position, Duration, Remaining }

/**
 * Elapsed, total or remaining time in the inherited style. Media3's PositionText and DurationText
 * pass a fresh style to Text, which drops the provided mono face. Remaining reads "-31:07".
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileTimeText(player: Player, time: PlayerTime) {
    val progress = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1_000)
    val text = when (time) {
        PlayerTime.Position -> Util.getStringForTime(progress.currentPositionMs)
        PlayerTime.Duration -> Util.getStringForTime(progress.durationMs)
        PlayerTime.Remaining ->
            "-" + Util.getStringForTime((progress.durationMs - progress.currentPositionMs).coerceAtLeast(0L))
    }
    Text(text)
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
        Slider(
            value = progress,
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
