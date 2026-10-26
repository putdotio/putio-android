package io.putdotio.android.playback

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.MobileRow
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.R as DesignR
import kotlinx.coroutines.delay

internal const val MOBILE_AUDIO_PREVIOUS_TAG = "mobile-audio-previous"
internal const val MOBILE_AUDIO_NEXT_TAG = "mobile-audio-next"
internal const val MOBILE_SLEEP_TIMER_CHIP_TAG = "mobile-sleep-timer-chip"
internal const val MOBILE_UP_NEXT_CHIP_TAG = "mobile-up-next-chip"
internal const val MOBILE_NOW_PLAYING_SHEET_TAG = "mobile-now-playing-sheet"

/**
 * Now Playing (DESIGN.md Player): a large type glyph, the filename and its folder, a scrubber with
 * mono times, an 88dp neutral play and pause between the skip buttons and the 15 and 30-second
 * seeks, then speed, sleep timer and Up next chips. Back sits above it, from the player screen, so
 * every audio state keeps the same way out.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileNowPlayingScreen(
    player: Player,
    title: String,
    neighbours: PlaybackNeighbours,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    onScrub: () -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onAudioSelectionChanged: (AudioSelection) -> Unit,
    modifier: Modifier = Modifier,
    sleepTimer: MobileSleepTimer = MobileSleepTimer.Shared,
    castButton: @Composable (Modifier) -> Unit = {},
    /** "Playing on Living room TV" while a receiver plays the file; the sleep timer then stays here. */
    castLabel: String? = null,
) {
    val options = rememberPlayerOptionsSnapshot(player)
    var sheet by rememberSaveable { mutableStateOf<NowPlayingSheet?>(null) }
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        NowPlayingTopBar(
            canChooseAudio = options.audioTracks.size > 1,
            onAudioTrack = { sheet = NowPlayingSheet.AudioTrack },
            castButton = castButton,
            castLabel = castLabel,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val details: @Composable (Modifier) -> Unit = { detailsModifier ->
                NowPlayingDetails(
                    player = player,
                    title = title,
                    neighbours = neighbours,
                    seekEnabled = seekEnabled,
                    onSeek = onSeek,
                    onScrub = onScrub,
                    onPlay = onPlay,
                    chips = {
                        // A receiver without speed control leaves the chip out.
                        if (castLabel == null || options.canChangeSpeed) {
                            MobileSpeedChip(options, { sheet = NowPlayingSheet.Speed }, onPage = true)
                        }
                        if (castLabel == null) SleepTimerChip(sleepTimer) { sheet = NowPlayingSheet.SleepTimer }
                        UpNextChip(neighbours.next) { sheet = NowPlayingSheet.UpNext }
                    },
                    modifier = detailsModifier,
                )
            }
            if (maxWidth >= WIDE_LAYOUT_WIDTH && maxWidth > maxHeight) {
                // Side by side: the details keep room for the transport and scroll; the glyph takes the rest,
                // and a pane too short for the smallest glyph leaves it out.
                val artwork = minOf(maxHeight - 16.dp, maxWidth - ARTWORK_INSET - WIDE_DETAILS_WIDTH, MAX_ARTWORK)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    if (artwork >= MIN_ARTWORK) {
                        NowPlayingArtwork(
                            Modifier.padding(start = ARTWORK_INSET, top = 8.dp, bottom = 8.dp).size(artwork),
                        )
                    }
                    details(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            } else {
                val reserve = DETAILS_HEIGHT * LocalDensity.current.fontScale.coerceAtLeast(1f)
                val artwork = minOf(maxWidth - ARTWORK_INSET * 2, maxHeight - ARTWORK_TOP - reserve, MAX_ARTWORK)
                    .coerceAtLeast(MIN_ARTWORK)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NowPlayingArtwork(Modifier.padding(top = ARTWORK_TOP).size(artwork))
                    details(Modifier.widthIn(max = MAX_ARTWORK + ARTWORK_INSET * 2))
                }
            }
        }
    }
    NowPlayingSheets(
        sheet = sheet,
        onSheet = { sheet = it },
        player = player,
        options = options,
        next = neighbours.next,
        sleepTimer = sleepTimer,
        onPlay = onPlay,
        onAudioSelectionChanged = onAudioSelectionChanged,
    )
}

/**
 * Room for the top bar's back, which the player screen draws, then where a receiver plays the file,
 * Cast, and More when there is a choice in it.
 */
@Composable
private fun NowPlayingTopBar(
    canChooseAudio: Boolean,
    onAudioTrack: () -> Unit,
    castButton: @Composable (Modifier) -> Unit,
    castLabel: String?,
) {
    Row(
        Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.width(TOP_BAR_BACK_ROOM))
        if (castLabel == null) {
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                castLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(MOBILE_CAST_LABEL_TAG),
            )
        }
        castButton(Modifier)
        if (!canChooseAudio) return@Row
        var expanded by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    painterResource(R.drawable.ic_ph_dots_three_vertical),
                    contentDescription = stringResource(R.string.mobile_action_more_options),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mobile_playback_audio_track)) },
                    onClick = {
                        expanded = false
                        onAudioTrack()
                    },
                )
            }
        }
    }
}

@Composable
private fun NowPlayingArtwork(modifier: Modifier) {
    Surface(
        modifier = modifier.aspectRatio(1f).testTag(MOBILE_AUDIO_COVER_TAG),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(DesignR.drawable.ic_ph_file_audio_fill),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ARTWORK_GLYPH),
            )
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun NowPlayingDetails(
    player: Player,
    title: String,
    neighbours: PlaybackNeighbours,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    onScrub: () -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    chips: @Composable () -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        MobileMiddleCutText(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = TITLE_SIZE,
                lineHeight = TITLE_LINE,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(start = ARTWORK_INSET, end = ARTWORK_INSET, top = 28.dp),
        )
        neighbours.folderName?.let { folder ->
            Text(
                folder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = ARTWORK_INSET, end = ARTWORK_INSET, top = 4.dp),
            )
        }
        NowPlayingTimeline(player, onScrub, Modifier.padding(top = 6.dp))
        NowPlayingTransport(player, seekEnabled, onSeek, neighbours, onPlay)
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { chips() }
    }
}

/**
 * The scrubber and, under it, the elapsed and remaining times in mono. The times sit 10dp under
 * the track's centre, inside the lower half of its 48dp touch band, which nothing else takes.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun NowPlayingTimeline(player: Player, onScrub: () -> Unit, modifier: Modifier) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Box(Modifier.padding(horizontal = SCRUBBER_INSET)) {
                MobileProgressSlider(
                    player,
                    onScrub,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    thumbSize = 16.dp,
                )
            }
            ProvideTextStyle(
                MaterialTheme.typography.bodySmall.copy(
                    fontSize = PutioTypeScale.labelSm.fontSize,
                    lineHeight = PutioTypeScale.labelSm.lineHeight,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ARTWORK_INSET),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MobileTimeText(player, PlayerTime.Position)
                    MobileTimeText(player, PlayerTime.Remaining)
                }
            }
        },
    ) { measurables, limits ->
        val loose = limits.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val (slider, times) = measurables.map { it.measure(loose) }
        val timesTop = slider.height / 2 + TIMES_BELOW_TRACK_CENTRE.roundToPx()
        layout(limits.maxWidth, maxOf(slider.height, timesTop + times.height)) {
            slider.placeRelative(0, 0)
            times.placeRelative(0, timesTop)
        }
    }
}

/**
 * Back 15 and Previous, the 88dp play and pause, then Next and Forward 30, on the board's spacing.
 * A narrower window closes the gaps first, then shrinks the play button down to the small ones.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun NowPlayingTransport(
    player: Player,
    seekEnabled: Boolean,
    onSeek: (SeekDirection) -> Unit,
    neighbours: PlaybackNeighbours,
    onPlay: (PlaybackTarget) -> Unit,
) {
    // The chips' 48dp touch band adds 8dp above them, so the bottom padding leaves the board's 20dp.
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val buttons = TRANSPORT_TARGET * 4
        val room = maxWidth - buttons - PLAY_RING
        val gaps = (room / (SMALL_GAP * 2 + BIG_GAP * 2)).coerceIn(0f, 1f)
        val ring = (maxWidth - buttons).coerceIn(TRANSPORT_TARGET, PLAY_RING)
        // Narrower than the five 48dp targets, the row wraps rather than squeeze them.
        FlowRow(
            horizontalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AudioSeekButton(SeekDirection.Backward, seekEnabled) { onSeek(SeekDirection.Backward) }
            Spacer(Modifier.width(SMALL_GAP * gaps))
            SkipButton(neighbours.previous, R.drawable.ic_ph_skip_back_fill, R.string.mobile_now_playing_previous,
                MOBILE_AUDIO_PREVIOUS_TAG, onPlay)
            Spacer(Modifier.width(BIG_GAP * gaps))
            PlayPauseButton(player, ring)
            Spacer(Modifier.width(BIG_GAP * gaps))
            SkipButton(neighbours.next, R.drawable.ic_ph_skip_forward_fill, R.string.mobile_now_playing_next,
                MOBILE_AUDIO_NEXT_TAG, onPlay)
            Spacer(Modifier.width(SMALL_GAP * gaps))
            AudioSeekButton(SeekDirection.Forward, seekEnabled) { onSeek(SeekDirection.Forward) }
        }
    }
}

@Composable
private fun AudioSeekButton(direction: SeekDirection, enabled: Boolean, onClick: () -> Unit) {
    val seconds = (AudioSeekIntervals.millis(direction) / 1_000L).toInt()
    val description = seekDescription(direction, seconds)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .testTag(if (direction == SeekDirection.Backward) MOBILE_SEEK_BACK_TAG else MOBILE_SEEK_FORWARD_TAG)
            .semantics { contentDescription = description },
    ) {
        Icon(
            painterResource(
                if (direction == SeekDirection.Backward) {
                    R.drawable.ic_ph_arrow_counter_clockwise
                } else {
                    R.drawable.ic_ph_arrow_clockwise
                },
            ),
            contentDescription = null,
            modifier = Modifier.size(TRANSPORT_GLYPH),
        )
    }
}

/** Plays [target], the file before or after this one in its folder; off while there is none. */
@Composable
private fun SkipButton(
    target: PlaybackTarget?,
    @DrawableRes icon: Int,
    @StringRes label: Int,
    tag: String,
    onPlay: (PlaybackTarget) -> Unit,
) {
    IconButton(
        onClick = { target?.let(onPlay) },
        enabled = target != null,
        modifier = Modifier.testTag(tag),
    ) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(TRANSPORT_GLYPH))
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun PlayPauseButton(player: Player, ring: Dp) {
    val playPause = rememberPlayPauseButtonState(player)
    val buffering = rememberBuffering(player)
    Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
        if (buffering) {
            val loading = stringResource(R.string.mobile_state_loading)
            CircularProgressIndicator(
                modifier = Modifier.size(ring).semantics { contentDescription = loading },
                strokeWidth = 2.dp,
            )
        }
        val fill = MaterialTheme.colorScheme.onSurface
        FilledIconButton(
            onClick = playPause::onClick,
            enabled = playPause.isEnabled,
            modifier = Modifier.size(ring - PLAY_RING_MARGIN * 2),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = fill,
                contentColor = MaterialTheme.colorScheme.background,
                disabledContainerColor = fill,
                disabledContentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Icon(
                painterResource(
                    if (playPause.showPlay) DesignR.drawable.ic_ph_play_fill else DesignR.drawable.ic_ph_pause_fill,
                ),
                contentDescription = stringResource(
                    if (playPause.showPlay) R.string.mobile_now_playing_play else R.string.mobile_now_playing_pause,
                ),
                modifier = Modifier.size(PLAY_GLYPH * ((ring - PLAY_RING_MARGIN * 2) / PLAY_BUTTON)),
            )
        }
    }
}

/** "Sleep timer" while off; while on, the time left in mono on a filled chip. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun SleepTimerChip(timer: MobileSleepTimer, onClick: () -> Unit) {
    val run by timer.run.collectAsState()
    val remaining = run?.let { rememberRemaining(timer, it) }
    val left = remaining?.let { Util.getStringForTime(it) }
    MobilePlayerChip(
        label = left ?: stringResource(R.string.mobile_now_playing_sleep_timer),
        description = left?.let { stringResource(R.string.mobile_now_playing_sleep_timer_left, it) },
        onClick = onClick,
        leadingIcon = R.drawable.ic_ph_moon,
        mono = left != null,
        onPage = true,
        selected = left != null,
        modifier = Modifier.testTag(MOBILE_SLEEP_TIMER_CHIP_TAG),
    )
}

/** What is left of [run], counted down once a second. */
@Composable
private fun rememberRemaining(timer: MobileSleepTimer, run: MobileSleepTimer.Run): Long {
    var remaining by remember(run) { mutableLongStateOf(timer.remainingMillis(run)) }
    LaunchedEffect(run) {
        while (remaining > 0L) {
            delay(remaining % TICK_MILLIS + 1L)
            remaining = timer.remainingMillis(run)
        }
    }
    return remaining
}

@Composable
private fun UpNextChip(next: PlaybackTarget?, onClick: () -> Unit) {
    MobilePlayerChip(
        label = stringResource(R.string.mobile_now_playing_up_next),
        description = null,
        onClick = onClick,
        leadingIcon = R.drawable.ic_ph_queue,
        enabled = next != null,
        onPage = true,
        modifier = Modifier.testTag(MOBILE_UP_NEXT_CHIP_TAG),
    )
}

internal enum class NowPlayingSheet(@StringRes val title: Int) {
    Speed(R.string.mobile_playback_speed),
    SleepTimer(R.string.mobile_now_playing_sleep_timer),
    UpNext(R.string.mobile_now_playing_up_next),
    AudioTrack(R.string.mobile_playback_audio_track),
}

/** Each chip's choices in a bottom sheet; a choice that plays or sets something closes it. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingSheets(
    sheet: NowPlayingSheet?,
    onSheet: (NowPlayingSheet?) -> Unit,
    player: Player,
    options: PlayerOptionsSnapshot,
    next: PlaybackTarget?,
    sleepTimer: MobileSleepTimer,
    onPlay: (PlaybackTarget) -> Unit,
    onAudioSelectionChanged: (AudioSelection) -> Unit,
) {
    if (sheet == null) return
    val close = { onSheet(null) }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
                .testTag(MOBILE_NOW_PLAYING_SHEET_TAG),
        ) {
            Text(
                stringResource(sheet.title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).semantics { heading() },
            )
            when (sheet) {
                NowPlayingSheet.Speed -> MobileSpeedSheetContent(player, options)
                NowPlayingSheet.SleepTimer -> SleepTimerChoices(sleepTimer, close)
                NowPlayingSheet.UpNext -> next?.let { target ->
                    UpNextRow(target) {
                        close()
                        onPlay(target)
                    }
                }
                NowPlayingSheet.AudioTrack -> MobileAudioSheetContent(player, options, onAudioSelectionChanged, close)
            }
        }
    }
}

@Composable
private fun SleepTimerChoices(timer: MobileSleepTimer, onDone: () -> Unit) {
    val run by timer.run.collectAsState()
    Column(Modifier.selectableGroup()) {
        PlaybackOption(
            label = AnnotatedString(stringResource(R.string.mobile_now_playing_sleep_off)),
            selected = run == null,
            enabled = true,
            onClick = {
                timer.cancel()
                onDone()
            },
        )
        MobileSleepTimer.CHOICES.forEach { minutes ->
            PlaybackOption(
                label = sleepChoiceLabel(minutes),
                selected = run?.minutes == minutes,
                enabled = true,
                onClick = {
                    timer.start(minutes)
                    onDone()
                },
            )
        }
    }
}

/** "15 minutes", "1 hour", with the number in mono. */
@Composable
private fun sleepChoiceLabel(minutes: Int) = buildAnnotatedString {
    val hours = minutes % MINUTES_PER_HOUR == 0
    val count = if (hours) minutes / MINUTES_PER_HOUR else minutes
    val words = if (hours) {
        pluralStringResource(R.plurals.mobile_now_playing_sleep_hours, count, count)
    } else {
        pluralStringResource(R.plurals.mobile_now_playing_sleep_minutes, count, count)
    }
    val number = count.toString()
    val at = words.indexOf(number)
    if (at < 0) {
        append(words)
    } else {
        append(words.substring(0, at))
        withStyle(
            SpanStyle(
                fontFamily = PutioTypeScale.mono.fontFamily,
                fontFeatureSettings = PutioTypeScale.mono.fontFeatureSettings,
            ),
        ) {
            append(number)
        }
        append(words.substring(at + number.length))
    }
}

/** The next file in the folder, as a file row; a tap plays it. */
@Composable
private fun UpNextRow(next: PlaybackTarget, onClick: () -> Unit) {
    MobileRow(
        leading = {
            Icon(
                painterResource(DesignR.drawable.ic_ph_file_audio_fill),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        },
        headline = { MobileMiddleCutText(next.name) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private val TOP_BAR_HEIGHT = 64.dp

/** The player screen's 48dp back sits at the bar's start. */
private val TOP_BAR_BACK_ROOM = 48.dp
private val WIDE_LAYOUT_WIDTH = 600.dp

/** Beside the glyph, the details need the transport's 288dp and their 16dp gutters. */
private val WIDE_DETAILS_WIDTH = 320.dp
private val ARTWORK_INSET = 32.dp
private val ARTWORK_TOP = 24.dp
private val ARTWORK_GLYPH = 120.dp
private val MIN_ARTWORK = 96.dp
private val MAX_ARTWORK = 480.dp

/** What the details under the glyph need at the default font scale, so the glyph leaves them room. */
private val DETAILS_HEIGHT = 368.dp

/** The board's Now Playing title: 20sp medium on a 28sp line, wrapping to two lines before the cut. */
private val TITLE_SIZE = 20.sp
private val TITLE_LINE = 28.sp

/** Material's slider insets its track by half the 16dp thumb, so the track spans the 32dp gutters. */
private val SCRUBBER_INSET = ARTWORK_INSET - 8.dp
private val TIMES_BELOW_TRACK_CENTRE = 10.dp
private val TRANSPORT_GLYPH = 30.dp
private val PLAY_BUTTON = 88.dp
private val PLAY_GLYPH = 38.dp

/** The buffering ring circles the 88dp button, 4dp out. */
private val PLAY_RING_MARGIN = 4.dp
private val PLAY_RING = PLAY_BUTTON + PLAY_RING_MARGIN * 2
private val TRANSPORT_TARGET = 48.dp

/** The board's 28dp between glyphs, less the 48dp touch targets' and the ring's own margins. */
private val SMALL_GAP = 10.dp
private val BIG_GAP = 15.dp
private const val TICK_MILLIS = 1_000L
private const val MINUTES_PER_HOUR = 60
