package io.putdotio.android.playback

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.putioMono

/**
 * An outlined chip on the video, at least 48dp tall to touch, named for what it changes. On a page
 * rather than over video, [onPage] gives it the page's outline; [selected] fills it while its
 * setting is on.
 */
@Composable
internal fun MobilePlayerChip(
    label: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes leadingIcon: Int? = null,
    @DrawableRes trailingIcon: Int? = null,
    mono: Boolean = false,
    enabled: Boolean = true,
    onPage: Boolean = false,
    selected: Boolean = false,
) {
    val content = MaterialTheme.colorScheme.onSurface
    val border = when {
        selected -> Color.Transparent
        onPage -> MaterialTheme.colorScheme.outline
        else -> content.copy(alpha = CHIP_BORDER_ALPHA)
    }
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge.let { if (mono) it.putioMono() else it },
            )
        },
        leadingIcon = leadingIcon?.let { { Icon(painterResource(it), null, Modifier.size(CHIP_ICON_SIZE)) } },
        trailingIcon = trailingIcon?.let { { Icon(painterResource(it), null, Modifier.size(CHIP_ICON_SIZE)) } },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
            labelColor = content,
            leadingIconContentColor = content,
            trailingIconContentColor = content,
        ),
        border = BorderStroke(1.dp, border),
        modifier = modifier.semantics { if (description != null) contentDescription = description },
    )
}

private val CHIP_ICON_SIZE = 16.dp
private const val CHIP_BORDER_ALPHA = 0.3f

@Composable
internal fun MobileAudioChip(snapshot: PlayerOptionsSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (snapshot.audioTracks.isEmpty()) return
    val labels = audioTrackLabels(snapshot.audioTracks)
    val current = snapshot.currentAudio?.let { labels[snapshot.audioTracks.indexOf(it)] }
        ?: stringResource(R.string.mobile_playback_audio_control)
    MobilePlayerChip(
        label = current,
        description = stringResource(R.string.mobile_playback_audio_chip, current),
        onClick = onClick,
        leadingIcon = R.drawable.ic_ph_speaker_high,
        modifier = modifier,
    )
}

@Composable
internal fun MobileSpeedChip(
    snapshot: PlayerOptionsSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPage: Boolean = false,
) {
    val value = stringResource(R.string.mobile_playback_speed_value, formatPlaybackSpeed(snapshot.speed))
    MobilePlayerChip(
        label = value,
        description = stringResource(R.string.mobile_playback_speed_chip, value),
        onClick = onClick,
        leadingIcon = R.drawable.ic_ph_gauge,
        mono = true,
        modifier = modifier,
        onPage = onPage,
    )
}

/** Audio: the tracks by language, each with its channels and codec. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileAudioSheetContent(
    player: Media3Player,
    snapshot: PlayerOptionsSnapshot,
    onAudioSelectionChanged: (AudioSelection) -> Unit,
    onDone: () -> Unit,
) {
    val tracks = snapshot.audioTracks
    val labels = audioTrackLabels(tracks)
    val current = snapshot.currentAudio
    Column(Modifier.selectableGroup()) {
        tracks.forEachIndexed { index, track ->
            val details = audioTrackDetails(track)
            MobileSideSheetOption(
                label = labels[index],
                selected = track == current,
                enabled = snapshot.canSelectTracks,
                supporting = details?.let { { Text(it) } },
                onClick = {
                    onAudioSelectionChanged(AudioSelection.Track(track.identity))
                    player.trackSelectionParameters = snapshot.parameters.withAudioTrack(track)
                    onDone()
                },
            )
        }
    }
}

/**
 * Speed: the value large between − and +, which step through the presets; a stepped track over
 * the presets; then quick choices. Every control snaps to a preset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileSpeedSheetContent(player: Media3Player, snapshot: PlayerOptionsSnapshot) {
    val speed = snapshot.speed
    val enabled = snapshot.canChangeSpeed
    val slower = adjacentPlaybackSpeed(speed, faster = false)
    val faster = adjacentPlaybackSpeed(speed, faster = true)
    val value = stringResource(R.string.mobile_playback_speed_value, formatPlaybackSpeed(speed))
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedStepButton(
            icon = R.drawable.ic_ph_minus,
            description = stringResource(
                R.string.mobile_playback_speed_slower,
                stringResource(R.string.mobile_playback_speed_value, formatPlaybackSpeed(slower ?: speed)),
            ),
            enabled = enabled && slower != null,
            onClick = { slower?.let(player::setPlaybackSpeed) },
        )
        // The buttons keep their 56dp; the value takes what is left and shrinks to fit large text.
        BasicText(
            buildAnnotatedString {
                append(formatPlaybackSpeed(speed))
                withStyle(SpanStyle(fontSize = SPEED_UNIT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append("×")
                }
            },
            style = PutioTypeScale.mono.copy(
                fontSize = SPEED_VALUE_SIZE,
                lineHeight = SPEED_VALUE_LINE,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(maxFontSize = SPEED_VALUE_SIZE),
            modifier = Modifier.weight(1f).semantics {
                contentDescription = value
                liveRegion = LiveRegionMode.Polite
            },
        )
        SpeedStepButton(
            icon = R.drawable.ic_ph_plus,
            description = stringResource(
                R.string.mobile_playback_speed_faster,
                stringResource(R.string.mobile_playback_speed_value, formatPlaybackSpeed(faster ?: speed)),
            ),
            enabled = enabled && faster != null,
            onClick = { faster?.let(player::setPlaybackSpeed) },
        )
    }
    SpeedTrack(speed, enabled, value, onSpeed = player::setPlaybackSpeed)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val rangeStyle = MaterialTheme.typography.bodySmall.putioMono()
            .copy(fontSize = PutioTypeScale.labelSm.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(PLAYBACK_SPEEDS.first(), PLAYBACK_SPEEDS.last()).forEach { end ->
            Text(stringResource(R.string.mobile_playback_speed_value, formatPlaybackSpeed(end)), style = rangeStyle)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QUICK_PLAYBACK_SPEEDS.forEach { choice ->
            QuickSpeed(
                label = stringResource(R.string.mobile_playback_speed_value, formatPlaybackSpeed(choice)),
                selected = speed == choice,
                enabled = enabled,
                onClick = { player.setPlaybackSpeed(choice) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SpeedStepButton(@DrawableRes icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(56.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(painterResource(icon), contentDescription = description)
    }
}

/** The eight presets as stops: passed ones and the track up to the current stop in yellow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedTrack(speed: Float, enabled: Boolean, value: String, onSpeed: (Float) -> Unit) {
    val index = playbackSpeedStop(speed)
    val last = PLAYBACK_SPEEDS.lastIndex
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    val stop = MaterialTheme.colorScheme.outline
    val label = stringResource(R.string.mobile_playback_speed)
    Slider(
        value = index.toFloat(),
        onValueChange = { onSpeed(PLAYBACK_SPEEDS[it.toInt().coerceIn(0, last)]) },
        valueRange = 0f..last.toFloat(),
        steps = last - 1,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SPEED_TRACK_INSET).semantics {
            contentDescription = label
            stateDescription = value
        },
        thumb = { Box(Modifier.size(SPEED_THUMB).background(active, CircleShape)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(SPEED_THUMB)) {
                val radius = SPEED_THUMB.toPx() / 2
                val trackHeight = SPEED_TRACK_HEIGHT.toPx()
                val start = radius
                val width = size.width - radius * 2
                val y = size.height / 2
                val corner = CornerRadius(trackHeight / 2)
                drawRoundRect(inactive, Offset(start, y - trackHeight / 2), Size(width, trackHeight), corner)
                val reached = width * index / last
                drawRoundRect(active, Offset(start, y - trackHeight / 2), Size(reached, trackHeight), corner)
                for (stopIndex in 0..last) {
                    val x = start + width * stopIndex / last
                    drawCircle(if (stopIndex < index) active else stop, SPEED_STOP.toPx() / 2, Offset(x, y))
                }
            }
        },
    )
}

@Composable
private fun QuickSpeed(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val selection = modifier.height(40.dp).semantics {
        this.selected = selected
        role = Role.RadioButton
    }
    val padding = PaddingValues(horizontal = 4.dp)
    val text: @Composable () -> Unit = {
        BasicText(
            label,
            style = PutioTypeScale.mono.copy(color = LocalContentColor.current),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(maxFontSize = PutioTypeScale.mono.fontSize),
        )
    }
    if (selected) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = selection,
            contentPadding = padding,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) { text() }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = selection,
            contentPadding = padding,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) { text() }
    }
}

private val SPEED_VALUE_SIZE = 48.sp
private val SPEED_VALUE_LINE = 56.sp
private val SPEED_UNIT_SIZE = 24.sp
private val SPEED_THUMB = 16.dp
private val SPEED_STOP = 8.dp
private val SPEED_TRACK_HEIGHT = 4.dp
private val SPEED_TRACK_INSET = 24.dp
