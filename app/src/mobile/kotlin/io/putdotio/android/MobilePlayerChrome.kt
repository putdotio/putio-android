package io.putdotio.android

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.indicators.ProgressIndicator
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState

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
) {
    if (isAudio) {
        MobileAudioLayout(player, title, seekEnabled, onSeek, onScrub, settings, modifier)
    } else if (visible) {
        MobileVideoLayout(player, title, seekEnabled, onSeek, onScrub, settings, modifier, onBack)
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
                    painterResource(R.drawable.ic_ph_file_audio_fill),
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
) {
    val scrim = MaterialTheme.colorScheme.background
    BoxWithConstraints(modifier.fillMaxSize()) {
        var contentHeight by remember { mutableIntStateOf(0) }
        val scrollState = rememberScrollState()
        // A scroll modifier intercepts the sibling video's taps even when scrolling is disabled.
        val overflow = if (contentHeight > constraints.maxHeight) {
            Modifier.verticalScroll(scrollState)
        } else {
            Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
        }
        Column(
            Modifier.fillMaxWidth().then(overflow).heightIn(min = maxHeight)
                .onSizeChanged { contentHeight = it.height },
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0.9f), scrim.copy(alpha = 0f))))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_ph_arrow_left),
                        contentDescription = stringResource(R.string.mobile_action_back),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                )
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                MobileTransport(player, seekEnabled, onSeek, onVideo = true)
            }
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0f), scrim.copy(alpha = 0.95f))))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            ) {
                MobilePlayerTimeline(player, onScrub)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) { settings() }
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileTransport(
    player: Player,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    modifier: Modifier = Modifier,
    onVideo: Boolean = false,
) {
    val playPause = rememberPlayPauseButtonState(player)
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
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileSeekButton(SeekDirection.Backward, seekEnabled, { onSeek(SeekDirection.Backward) }, onVideo = onVideo)
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
                modifier = Modifier.size(if (onVideo) 64.dp else 72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (onVideo) {
                        MaterialTheme.colorScheme.background.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    contentColor = if (onVideo) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            ) {
                Icon(
                    painterResource(
                        if (playPause.showPlay) R.drawable.ic_ph_play_fill else R.drawable.ic_ph_pause_fill,
                    ),
                    contentDescription = stringResource(
                        if (playPause.showPlay) R.string.mobile_now_playing_play else R.string.mobile_now_playing_pause,
                    ),
                    modifier = Modifier.size(if (onVideo) 40.dp else 32.dp),
                )
            }
        }
        MobileSeekButton(SeekDirection.Forward, seekEnabled, { onSeek(SeekDirection.Forward) }, onVideo = onVideo)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobilePlayerTimeline(player: Player, onScrub: () -> Unit) {
    Column {
        MobileProgressSlider(player, onScrub)
        ProvideTextStyle(
            MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PositionText(player)
                DurationText(player)
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileProgressSlider(player: Player, onScrub: () -> Unit = {}) {
    var width by remember { mutableIntStateOf(0) }
    var dragging by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableFloatStateOf(0f) }
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            if (interaction is DragInteraction.Cancel || interaction is PressInteraction.Cancel) dragging = false
        }
    }
    val colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant)
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
                    thumbSize = DpSize(12.dp, 12.dp),
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
