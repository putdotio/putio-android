package io.putdotio.android.tv.player

import androidx.compose.runtime.getValue
import io.putdotio.android.playback.R as PlaybackR
import androidx.compose.runtime.setValue
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.tvSafeAreaPadding
import kotlinx.coroutines.delay

internal const val TV_PLAYER_CONTROLS_TAG = "tv-player-controls"
internal const val TV_PLAYER_SEEK_BAR_TAG = "tv-player-seek-bar"
internal const val TV_PLAYER_ELAPSED_TAG = "tv-player-elapsed"
private const val TV_PLAYER_POSITION_POLL_MILLIS = 500L

internal data class TvPlayerChipLabels(
    val audio: String,
    val subtitles: String,
    val speed: String,
)

@Composable
internal fun TvPlayerControls(
    player: Player,
    title: String,
    paused: Boolean,
    scrubTargetMillis: Long?,
    startPositionMillis: Long,
    listingDurationMillis: Long?,
    chips: List<TvPlayerControl>,
    chipLabels: TvPlayerChipLabels,
    focused: TvPlayerControl,
    modifier: Modifier = Modifier,
) {
    var positionMillis by remember(player) {
        mutableLongStateOf(
            if (player.currentMediaItem == null) startPositionMillis else player.currentPosition.coerceAtLeast(0L),
        )
    }
    var durationMillis by remember(player) { mutableLongStateOf(player.duration) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) {
                    positionMillis = player.currentPosition.coerceAtLeast(0L)
                    durationMillis = player.duration
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, paused) {
        while (true) {
            if (player.currentMediaItem != null) positionMillis = player.currentPosition.coerceAtLeast(0L)
            durationMillis = player.duration
            if (paused) break
            delay(TV_PLAYER_POSITION_POLL_MILLIS)
        }
    }
    val shownMillis = scrubTargetMillis ?: positionMillis
    val knownDuration = knownDurationMillis(durationMillis, listingDurationMillis)
    val elapsed = playerTime(shownMillis)
    val seekBarDescription = knownDuration?.let {
        stringResource(R.string.tv_player_seek_bar, elapsed, playerTime(it))
    } ?: elapsed
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tvPlayerShade(MaterialTheme.colorScheme.scrim))
            .testTag(TV_PLAYER_CONTROLS_TAG),
    ) {
        Box(Modifier.fillMaxSize().tvPlayerEdges()) {
            TvPlayerTopBar(title, Modifier.align(Alignment.TopStart))
            TvPlayerCircles(paused = paused, focused = focused, modifier = Modifier.align(Alignment.Center))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                TvPlayerTimes(
                    elapsed = elapsed,
                    remaining = knownDuration?.let { "-" + playerTime((it - shownMillis).coerceAtLeast(0L)) },
                )
                TvSeekBar(
                    fraction = knownDuration?.let { (shownMillis.toFloat() / it).coerceIn(0f, 1f) } ?: 0f,
                    description = seekBarDescription,
                    scrubbing = scrubTargetMillis != null,
                    focused = focused == TvPlayerControl.SeekBar,
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                )
                TvPlayerChips(chips = chips, labels = chipLabels, focused = focused)
            }
        }
    }
}

internal fun Modifier.tvPlayerEdges(): Modifier = tvSafeAreaPadding().padding(vertical = PLAYER_EDGE_EXTRA)

@Composable
internal fun TvPlayerTopBar(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_arrow_left),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            style = PutioTypeScale.tvDetailTitle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvPlayerCircles(paused: Boolean, focused: TvPlayerControl, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CIRCLE_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvPlayerCircle(
            icon = R.drawable.ic_ph_arrow_counter_clockwise,
            description = stringResource(R.string.tv_player_seek_back),
            focused = focused == TvPlayerControl.Back,
            size = CIRCLE,
            restAlpha = CIRCLE_ALPHA,
            seconds = true,
        )
        TvPlayerCircle(
            icon = if (paused) DesignR.drawable.ic_ph_play_fill else DesignR.drawable.ic_ph_pause_fill,
            description = stringResource(if (paused) R.string.tv_player_play else R.string.tv_player_pause),
            focused = focused == TvPlayerControl.PlayPause,
            size = BIG_CIRCLE,
            restAlpha = BIG_CIRCLE_ALPHA,
        )
        TvPlayerCircle(
            icon = R.drawable.ic_ph_arrow_clockwise,
            description = stringResource(R.string.tv_player_seek_forward),
            focused = focused == TvPlayerControl.Forward,
            size = CIRCLE,
            restAlpha = CIRCLE_ALPHA,
            seconds = true,
        )
    }
}

@Composable
private fun TvPlayerCircle(
    @DrawableRes icon: Int,
    description: String,
    focused: Boolean,
    size: Dp,
    restAlpha: Float,
    seconds: Boolean = false,
) {
    val content = if (focused) TvFocus.content else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (focused) TvFocus.container else MaterialTheme.colorScheme.scrim.copy(alpha = restAlpha))
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                selected = focused
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * ICON_FRACTION),
        )
        if (seconds) {
            Text(
                text = SKIP_SECONDS_LABEL,
                style = PutioTypeScale.tvMono.copy(fontWeight = FontWeight.Medium),
                color = content,
                modifier = Modifier.offset(y = 1.dp),
            )
        }
    }
}

@Composable
private fun TvPlayerTimes(elapsed: String, remaining: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().height(TIMES_HEIGHT),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = MaterialTheme.colorScheme.onSurface
        val style = PutioTypeScale.tvMono.copy(fontSize = TIMES_SIZE)
        Text(
            text = elapsed,
            style = style,
            color = color,
            modifier = Modifier.testTag(TV_PLAYER_ELAPSED_TAG),
        )
        if (remaining != null) Text(text = remaining, style = style, color = color)
    }
}

@Composable
private fun TvSeekBar(
    fraction: Float,
    description: String,
    scrubbing: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val knob = (if (scrubbing) SEEK_KNOB_SCRUBBING else SEEK_KNOB) + if (focused) SEEK_RING * 2 else 0.dp
    val yellow = PutioDesignTokens.yellowSolid
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(SEEK_TRACK)
            .testTag(TV_PLAYER_SEEK_BAR_TAG)
            .clearAndSetSemantics {
                contentDescription = description
                selected = focused
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SEEK_TRACK)
                .clip(RoundedCornerShape(SEEK_TRACK / 2))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)),
        ) {
            Box(Modifier.fillMaxWidth(fraction).height(SEEK_TRACK).background(yellow))
        }
        Box(
            modifier = Modifier
                .offset(x = (maxWidth * fraction - knob / 2).coerceIn(0.dp, maxWidth - knob))
                .requiredSize(knob)
                .then(if (focused) Modifier.border(SEEK_RING, TvFocus.container, CircleShape) else Modifier)
                .padding(if (focused) SEEK_RING else 0.dp)
                .clip(CircleShape)
                .background(yellow),
        )
    }
}

@Composable
private fun TvPlayerChips(chips: List<TvPlayerControl>, labels: TvPlayerChipLabels, focused: TvPlayerControl) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            if (chip == TvPlayerControl.NextEpisode) Spacer(Modifier.weight(1f))
            val (icon, label, description) = when (chip) {
                TvPlayerControl.Audio -> Triple(
                    R.drawable.ic_ph_speaker_high,
                    labels.audio,
                    stringResource(PlaybackR.string.playback_audio_chip, labels.audio),
                )
                TvPlayerControl.Subtitles -> Triple(
                    R.drawable.ic_ph_subtitles,
                    labels.subtitles,
                    stringResource(PlaybackR.string.playback_subtitles_chip, labels.subtitles),
                )
                TvPlayerControl.Speed -> Triple(
                    R.drawable.ic_ph_gauge,
                    labels.speed,
                    stringResource(PlaybackR.string.playback_speed_chip, labels.speed),
                )
                else -> stringResource(PlaybackR.string.playback_next_episode).let {
                    Triple(R.drawable.ic_ph_skip_forward, it, it)
                }
            }
            TvPlayerChip(
                icon = icon,
                label = label,
                description = description,
                focused = chip == focused,
                mono = chip == TvPlayerControl.Speed,
                bounded = chip == TvPlayerControl.Audio || chip == TvPlayerControl.Subtitles,
            )
        }
    }
}

@Composable
private fun TvPlayerChip(
    @DrawableRes icon: Int,
    label: String,
    description: String,
    focused: Boolean,
    mono: Boolean,
    bounded: Boolean,
) {
    val shape = RoundedCornerShape(PutioRadius.small)
    val rest = MaterialTheme.colorScheme.onSurface
    val content = if (focused) TvFocus.content else rest
    Row(
        modifier = Modifier
            .height(CHIP_HEIGHT)
            .then(if (bounded) Modifier.widthIn(max = CHIP_MAX_WIDTH) else Modifier)
            .clip(shape)
            .background(if (focused) TvFocus.container else Color.Transparent)
            .border(1.dp, if (focused) TvFocus.container else rest.copy(alpha = TRACK_ALPHA), shape)
            .padding(horizontal = CHIP_PADDING)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                selected = focused
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(
            text = label,
            style = if (mono) PutioTypeScale.tvMono else PutioTypeScale.tvBody.copy(fontWeight = FontWeight.Medium),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private fun knownDurationMillis(playerMillis: Long, listingMillis: Long?): Long? =
    playerMillis.takeIf { it != C.TIME_UNSET && it > 0L } ?: listingMillis?.takeIf { it > 0L }

internal fun playerTime(millis: Long): String =
    DateUtils.formatElapsedTime(millis / MILLIS_PER_SECOND)
        .let { if (it.length == MINUTES_LENGTH) it.removePrefix("0") else it }

private fun tvPlayerShade(scrim: Color) = Brush.verticalGradient(
    0f to scrim.copy(alpha = SHADE_TOP_ALPHA),
    SHADE_TOP_END to Color.Transparent,
    SHADE_BOTTOM_START to Color.Transparent,
    1f to scrim.copy(alpha = SHADE_BOTTOM_ALPHA),
)

private const val SHADE_TOP_ALPHA = 0.6f
private const val SHADE_TOP_END = 0.26f
private const val SHADE_BOTTOM_START = 0.58f
private const val SHADE_BOTTOM_ALPHA = 0.78f

private const val SKIP_SECONDS_LABEL = "10"
private const val MILLIS_PER_SECOND = 1_000L
private const val MINUTES_LENGTH = 5
private const val ICON_FRACTION = 0.55f
private const val CIRCLE_ALPHA = 0.55f
private const val BIG_CIRCLE_ALPHA = 0.6f
private const val TRACK_ALPHA = 0.3f
private val CIRCLE = 56.dp
private val BIG_CIRCLE = 72.dp
private val CIRCLE_GAP = 56.dp
private val CHIP_HEIGHT = 32.dp
private val CHIP_PADDING = 13.dp
private val PLAYER_EDGE_EXTRA = 3.dp
private val TIMES_HEIGHT = 19.dp
private val TIMES_SIZE = 13.sp
private val CHIP_MAX_WIDTH = 240.dp
private val SEEK_TRACK = 4.dp
private val SEEK_KNOB = 16.dp
private val SEEK_KNOB_SCRUBBING = 20.dp
private val SEEK_RING = 4.dp
