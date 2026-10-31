package io.putdotio.android.tv.player

import androidx.compose.runtime.getValue
import io.putdotio.android.playback.R as PlaybackR
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.playback.PLAYBACK_SPEEDS
import io.putdotio.android.playback.PlaybackAudioTrack
import io.putdotio.android.playback.PlaybackSubtitleTrack
import io.putdotio.android.playback.QUICK_PLAYBACK_SPEEDS
import io.putdotio.android.playback.SUBTITLE_OFFSET_LIMIT_MILLIS
import io.putdotio.android.playback.SUBTITLE_OFFSET_STEP_MILLIS
import io.putdotio.android.playback.SubtitleOrigin
import io.putdotio.android.playback.SubtitleSelection
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.SubtitleTextSize
import io.putdotio.android.playback.adjacentPlaybackSpeed
import io.putdotio.android.playback.TRACK_DETAIL_SEPARATOR
import io.putdotio.android.playback.audioTrackDetails
import io.putdotio.android.playback.audioTrackLabels
import io.putdotio.android.playback.formatPlaybackSpeed
import io.putdotio.android.playback.formatSubtitleOffset
import io.putdotio.android.playback.origin
import io.putdotio.android.playback.playbackAudioTracks
import io.putdotio.android.playback.playbackSubtitleTracks
import io.putdotio.android.playback.playbackSpeedStop
import io.putdotio.android.playback.putioSubtitle
import io.putdotio.android.playback.subtitleTrackDetails
import io.putdotio.android.playback.subtitleTrackLabels
import io.putdotio.android.playback.subtitleTrackName
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.TvSideSheet
import io.putdotio.sdk.files.PlaybackSubtitle

internal class TvTrackChoices(
    tracks: Tracks,
    private val parameters: TrackSelectionParameters,
    policy: SubtitleStartupPolicy?,
) {
    val audio = tracks.playbackAudioTracks()
    val subtitles = tracks.playbackSubtitleTracks()
    val subtitlesHidden = policy?.showSubtitles == false
    val subtitlesOn = C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes

    val currentAudio: PlaybackAudioTrack?
        get() = audio.singleOrNull { parameters.picked(it.group, it.trackIndex) } ?: audio.firstOrNull { it.selected }

    val currentSubtitle: PlaybackSubtitleTrack?
        get() = if (!subtitlesOn) {
            null
        } else {
            subtitles.singleOrNull { parameters.picked(it.group, it.trackIndex) }
                ?: subtitles.firstOrNull { it.selected }
        }

    fun chips(nextEpisode: Boolean): List<TvPlayerControl> = tvPlayerChips(audio.size, subtitlesHidden, nextEpisode)
}

private fun TrackSelectionParameters.picked(group: androidx.media3.common.TrackGroup, index: Int): Boolean =
    overrides[group]?.trackIndices?.contains(index) == true

@Composable
internal fun tvPlayerChipLabels(choices: TvTrackChoices, speed: Float): TvPlayerChipLabels {
    val audioNames = audioTrackLabels(choices.audio)
    val audio = choices.currentAudio?.let { audioNames[choices.audio.indexOf(it)] }
        ?: stringResource(R.string.tv_player_audio)
    val subtitles = choices.currentSubtitle?.let { subtitleTrackName(it, choices.subtitles.indexOf(it)) }
        ?: stringResource(R.string.tv_player_subtitles_off)
    val speedLabel = stringResource(R.string.tv_player_speed_value, formatPlaybackSpeed(speed))
    return TvPlayerChipLabels(audio, subtitles, speedLabel)
}

internal class TvPlayerSheetActions(
    val onAudio: (PlaybackAudioTrack) -> Unit,
    val onSubtitles: (SubtitleSelection) -> Unit,
    val onSizeAndTiming: () -> Unit,
    val onSpeed: (Float) -> Unit,
    val onTextSize: (SubtitleTextSize) -> Unit,
    val onOffset: (Long) -> Unit,
)

@Composable
internal fun TvPlayerSheets(
    sheet: TvPlayerSheet?,
    backFromSizeAndTiming: Boolean,
    returnFocus: FocusRequester,
    choices: TvTrackChoices,
    sidecar: List<PlaybackSubtitle>,
    speed: Float,
    textSize: SubtitleTextSize,
    offsetMillis: Long,
    actions: TvPlayerSheetActions,
    onDismiss: () -> Unit,
) {
    if (sheet == null) return
    if (choices.subtitlesHidden && (sheet == TvPlayerSheet.Subtitles || sheet == TvPlayerSheet.SizeAndTiming)) return
    val title = when (sheet) {
        TvPlayerSheet.Audio -> R.string.tv_player_audio
        TvPlayerSheet.Subtitles -> R.string.tv_player_subtitles
        TvPlayerSheet.Speed -> R.string.tv_player_speed
        TvPlayerSheet.SizeAndTiming -> PlaybackR.string.playback_size_and_timing
    }
    key(sheet) {
        TvSideSheet(onDismiss = onDismiss, returnFocus = returnFocus, title = stringResource(title)) { focus ->
            when (sheet) {
                TvPlayerSheet.Audio -> TvAudioSheet(choices, focus, actions.onAudio)
                TvPlayerSheet.Subtitles ->
                    TvSubtitlesSheet(
                        choices = choices,
                        sidecar = sidecar,
                        textSize = textSize,
                        offsetMillis = offsetMillis,
                        focus = focus,
                        focusSizeAndTiming = backFromSizeAndTiming,
                        actions = actions,
                    )
                TvPlayerSheet.Speed -> TvSpeedSheet(speed, focus, actions.onSpeed)
                TvPlayerSheet.SizeAndTiming ->
                    TvSizeAndTimingSheet(textSize, offsetMillis, focus, actions.onTextSize, actions.onOffset)
            }
        }
    }
}

@Composable
private fun ColumnScope.TvAudioSheet(
    choices: TvTrackChoices,
    focus: FocusRequester,
    onSelect: (PlaybackAudioTrack) -> Unit,
) {
    val names = audioTrackLabels(choices.audio)
    val current = choices.currentAudio
    val focusIndex = choices.audio.indexOf(current).coerceAtLeast(0)
    TvSheetRows {
        choices.audio.forEachIndexed { index, track ->
            TvSheetOption(
                label = names[index],
                detail = audioTrackDetails(track)?.let(::AnnotatedString),
                selected = track == current,
                onClick = { onSelect(track) },
                modifier = if (index == focusIndex) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

@Composable
private fun ColumnScope.TvSubtitlesSheet(
    choices: TvTrackChoices,
    sidecar: List<PlaybackSubtitle>,
    textSize: SubtitleTextSize,
    offsetMillis: Long,
    focus: FocusRequester,
    focusSizeAndTiming: Boolean,
    actions: TvPlayerSheetActions,
) {
    val current = choices.currentSubtitle
    val focusRow = Modifier.focusRequester(focus)
    val labels = subtitleTrackLabels(choices.subtitles, sidecar)
    val rows = choices.subtitles.mapIndexed { index, track -> Triple(index, track, track.putioSubtitle(sidecar)) }
    val inFile = rows.filter { it.third.origin() == SubtitleOrigin.File }
    val found = rows.filter { it.third.origin() != SubtitleOrigin.File }.sortedBy { it.third.origin() }
    TvSheetRows {
        TvSheetOption(
            label = stringResource(R.string.tv_player_subtitles_off),
            detail = null,
            selected = current == null,
            onClick = { actions.onSubtitles(SubtitleSelection.Off) },
            modifier = if (current == null && !focusSizeAndTiming) focusRow else Modifier,
        )
        listOf(
            PlaybackR.string.playback_subtitles_in_file to inFile,
            PlaybackR.string.playback_subtitles_found to found,
        )
            .filter { it.second.isNotEmpty() }
            .forEach { (heading, group) ->
                TvSheetLabel(stringResource(heading))
                group.forEach { (index, track, subtitle) ->
                    TvSheetOption(
                        label = labels[index],
                        detail = subtitleTrackDetails(track, subtitle)?.let(::AnnotatedString),
                        selected = track == current,
                        onClick = { actions.onSubtitles(SubtitleSelection.Track(track.identity)) },
                        modifier = if (track == current && !focusSizeAndTiming) focusRow else Modifier,
                    )
                }
            }
        val size = stringResource(textSize.label)
        TvSheetOption(
            label = stringResource(PlaybackR.string.playback_size_and_timing),
            detail = buildAnnotatedString {
                append(size)
                append(TRACK_DETAIL_SEPARATOR)
                withStyle(SpanStyle(fontFamily = PutioTypeScale.tvMono.fontFamily)) {
                    append(formatSubtitleOffset(offsetMillis))
                }
            },
            selected = false,
            leadingIcon = R.drawable.ic_ph_text_aa,
            trailingIcon = R.drawable.ic_ph_caret_right,
            role = Role.Button,
            onClick = actions.onSizeAndTiming,
            modifier = Modifier.padding(top = 4.dp).then(if (focusSizeAndTiming) focusRow else Modifier),
        )
    }
}

@Composable
private fun TvSpeedSheet(speed: Float, focus: FocusRequester, onSpeed: (Float) -> Unit) {
    val slower = adjacentPlaybackSpeed(speed, faster = false)
    val faster = adjacentPlaybackSpeed(speed, faster = true)
    val value = stringResource(R.string.tv_player_speed_value, formatPlaybackSpeed(speed))
    TvStepper(
        value = value,
        valueStyle = PutioTypeScale.tvMono.copy(fontSize = SPEED_VALUE_SIZE, lineHeight = SPEED_VALUE_LINE),
        buttons = TvStepButtons(SPEED_STEP_LABEL, SPEED_STEP_BUTTON_WIDTH, SPEED_STEPPER_HEIGHT),
        less = "−",
        lessDescription = stringResource(
            PlaybackR.string.playback_speed_slower,
            stringResource(R.string.tv_player_speed_value, formatPlaybackSpeed(slower ?: speed)),
        ),
        more = "+",
        moreDescription = stringResource(
            PlaybackR.string.playback_speed_faster,
            stringResource(R.string.tv_player_speed_value, formatPlaybackSpeed(faster ?: speed)),
        ),
        onLess = slower?.let { { onSpeed(it) } },
        onMore = faster?.let { { onSpeed(it) } },
        focus = focus,
        focusMore = faster != null,
    )
    val stop = playbackSpeedStop(speed)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PLAYBACK_SPEEDS.indices.forEach { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        if (index == stop) PutioDesignTokens.yellowSolid else PutioDesignTokens.componentBgActive,
                        RoundedCornerShape(PutioRadius.progress),
                    ),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(SPEED_RANGE_HEIGHT).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(PLAYBACK_SPEEDS.first(), PLAYBACK_SPEEDS.last()).forEach { end ->
            Text(
                stringResource(R.string.tv_player_speed_value, formatPlaybackSpeed(end)),
                style = PutioTypeScale.tvMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QUICK_PLAYBACK_SPEEDS.forEach { choice ->
            TvSheetChip(
                label = stringResource(R.string.tv_player_speed_value, formatPlaybackSpeed(choice)),
                selected = choice == speed,
                mono = true,
                onClick = { onSpeed(choice) },
            )
        }
    }
}

@Composable
private fun ColumnScope.TvSizeAndTimingSheet(
    textSize: SubtitleTextSize,
    offsetMillis: Long,
    focus: FocusRequester,
    onTextSize: (SubtitleTextSize) -> Unit,
    onOffset: (Long) -> Unit,
) {
    TvSheetLabel(stringResource(PlaybackR.string.playback_text_size))
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SubtitleTextSize.entries.forEach { size ->
            val name = stringResource(size.label)
            TvSheetChip(
                label = size.short,
                selected = size == textSize,
                mono = false,
                onClick = { onTextSize(size) },
                height = SIZE_CHIP_HEIGHT,
                fill = true,
                modifier = Modifier
                    .weight(1f)
                    .then(if (size == textSize) Modifier.focusRequester(focus) else Modifier)
                    .semantics { contentDescription = name },
            )
        }
    }
    TvSheetLabel(stringResource(PlaybackR.string.playback_subtitle_timing))
    TvStepper(
        value = formatSubtitleOffset(offsetMillis),
        valueStyle = PutioTypeScale.tvMono.copy(fontSize = OFFSET_VALUE_SIZE, lineHeight = OFFSET_VALUE_LINE),
        buttons = TvStepButtons(OFFSET_STEP_LABEL, width = null, height = null),
        less = "−0.1",
        lessDescription = stringResource(PlaybackR.string.playback_subtitle_earlier),
        more = "+0.1",
        moreDescription = stringResource(PlaybackR.string.playback_subtitle_later),
        onLess = { onOffset(offsetMillis - SUBTITLE_OFFSET_STEP_MILLIS) }
            .takeIf { offsetMillis > -SUBTITLE_OFFSET_LIMIT_MILLIS },
        onMore = { onOffset(offsetMillis + SUBTITLE_OFFSET_STEP_MILLIS) }
            .takeIf { offsetMillis < SUBTITLE_OFFSET_LIMIT_MILLIS },
        focus = null,
        focusMore = true,
    )
    TvSheetRows {
        TvSheetOption(
            label = stringResource(PlaybackR.string.playback_subtitle_reset),
            detail = null,
            selected = false,
            leadingIcon = R.drawable.ic_ph_arrow_counter_clockwise,
            role = Role.Button,
            enabled = offsetMillis != 0L,
            onClick = { onOffset(0L) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        stringResource(R.string.tv_player_caption_settings),
        style = PutioTypeScale.tvMeta.copy(fontSize = FOOTNOTE_SIZE, lineHeight = FOOTNOTE_LINE),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
    )
}

@Composable
private fun TvStepper(
    value: String,
    valueStyle: TextStyle,
    buttons: TvStepButtons,
    less: String,
    lessDescription: String,
    more: String,
    moreDescription: String,
    onLess: (() -> Unit)?,
    onMore: (() -> Unit)?,
    focus: FocusRequester?,
    focusMore: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .then(buttons.height?.let { Modifier.height(it) } ?: Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvStepButton(less, lessDescription, buttons, onLess, if (!focusMore) focus else null)
        Text(value, style = valueStyle, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        TvStepButton(more, moreDescription, buttons, onMore, if (focusMore) focus else null)
    }
}

@Composable
private fun TvStepButton(
    label: String,
    description: String,
    buttons: TvStepButtons,
    onClick: (() -> Unit)?,
    focus: FocusRequester?,
) {
    TvButton(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier
            .then(buttons.width?.let { Modifier.width(it) } ?: Modifier.widthIn(min = STEP_BUTTON_WIDTH))
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .semantics { contentDescription = description },
    ) {
        Text(
            label,
            style = buttons.label,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true),
        )
    }
}

private class TvStepButtons(val label: TextStyle, val width: Dp?, val height: Dp?)

@Composable
private fun ColumnScope.TvSheetRows(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
}

@Composable
private fun TvSheetLabel(text: String) {
    Text(
        text = text,
        style = PutioTypeScale.tvLabel.copy(lineHeight = DETAIL_LINE),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun TvSheetOption(
    label: String,
    detail: AnnotatedString?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes leadingIcon: Int? = null,
    @DrawableRes trailingIcon: Int? = null,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OPTION_MIN_HEIGHT)
            .semantics {
                this.role = role
                if (role == Role.RadioButton) this.selected = selected
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PutioRadius.medium)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PutioDesignTokens.componentBgActive else Color.Transparent,
            contentColor = colors.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.onSurfaceVariant,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier.heightIn(min = OPTION_MIN_HEIGHT).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(LEADING_SLOT), contentAlignment = Alignment.Center) {
                val icon = leadingIcon ?: R.drawable.ic_ph_check.takeIf { selected }
                if (icon != null) {
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = PutioTypeScale.tvBody.copy(fontSize = OPTION_LABEL_SIZE))
                if (detail != null) {
                    Text(
                        detail,
                        style = PutioTypeScale.tvMeta.copy(lineHeight = DETAIL_LINE),
                        color = if (focused) TvFocus.content else colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            TrailingIcon(trailingIcon, focused)
        }
    }
}

@Composable
private fun RowScope.TrailingIcon(@DrawableRes icon: Int?, focused: Boolean) {
    if (icon == null) return
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = if (focused) TvFocus.content else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp).align(Alignment.CenterVertically),
    )
}

@Composable
private fun TvSheetChip(
    label: String,
    selected: Boolean,
    mono: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = QUICK_CHIP_HEIGHT,
    fill: Boolean = false,
) {
    val shape = RoundedCornerShape(PutioRadius.small)
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(height)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.borderVariant, shape)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PutioDesignTokens.componentBgActive else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = if (mono) PutioTypeScale.tvMono else PutioTypeScale.tvBody)
        }
    }
}

private val OPTION_MIN_HEIGHT = 40.dp
private val OPTION_LABEL_SIZE = 15.sp
private val DETAIL_LINE = 15.sp
private val FOOTNOTE_SIZE = 11.sp
private val FOOTNOTE_LINE = 15.sp
private val SPEED_STEP_BUTTON_WIDTH = 44.dp
private val SPEED_STEP_LABEL = PutioTypeScale.tvBody.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium)
private val OFFSET_STEP_LABEL = PutioTypeScale.tvMono.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)
private val LEADING_SLOT = 20.dp
private val QUICK_CHIP_HEIGHT = 28.dp
private val SIZE_CHIP_HEIGHT = 32.dp
private val STEP_BUTTON_WIDTH = 56.dp
private val SPEED_VALUE_SIZE = 32.sp
private val SPEED_VALUE_LINE = 40.sp
private val SPEED_STEPPER_HEIGHT = 47.dp
private val SPEED_RANGE_HEIGHT = 16.dp
private val OFFSET_VALUE_SIZE = 22.sp
private val OFFSET_VALUE_LINE = 28.sp
