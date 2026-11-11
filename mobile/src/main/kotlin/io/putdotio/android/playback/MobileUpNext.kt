package io.putdotio.android.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player as Media3Player
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.R
import io.putdotio.android.design.putioMono
import io.putdotio.android.neutralTextButtonColors
import kotlin.math.ceil

internal const val MOBILE_UP_NEXT_TAG = "mobile-player-up-next"

internal enum class MobileUpNextStep {
    Wait,
    Count,
    PlayNow,
    Leave,
}

internal fun PlaybackUpNextLookup.stepAtEnd(pictureInPicture: Boolean): MobileUpNextStep =
    when (this) {
        PlaybackUpNextLookup.Pending -> MobileUpNextStep.Wait
        is PlaybackUpNextLookup.Found -> if (pictureInPicture) MobileUpNextStep.PlayNow else MobileUpNextStep.Count
        PlaybackUpNextLookup.Off, PlaybackUpNextLookup.FolderEnd, PlaybackUpNextLookup.Failed -> MobileUpNextStep.Leave
    }

@Stable
internal class MobileUpNextEnd(private val onPlay: State<(PlaybackTarget) -> Unit>) {
    var ended by mutableStateOf(false)
    var cancelled by mutableStateOf(false)
    var remainingMillis by mutableLongStateOf(PLAYBACK_UP_NEXT_MILLIS)

    fun play(target: PlaybackTarget) {
        if (cancelled) return
        cancelled = true
        onPlay.value(target)
    }
}

@Composable
internal fun rememberMobileUpNextEnd(
    fileId: Long,
    playbackState: Int,
    lookup: PlaybackUpNextLookup,
    pictureInPicture: Boolean,
    autoplay: Boolean,
    onPlaybackEnded: () -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
): MobileUpNextEnd {
    val currentOnPlay = rememberUpdatedState(onPlay)
    val end = remember(fileId) { MobileUpNextEnd(currentOnPlay) }
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    LaunchedEffect(end, playbackState) {
        if (playbackState != Media3Player.STATE_ENDED) {
            end.ended = false
            end.cancelled = false
            end.remainingMillis = PLAYBACK_UP_NEXT_MILLIS
        }
    }
    LaunchedEffect(end, end.ended, end.cancelled, lookup, pictureInPicture, autoplay) {
        if (!end.ended || end.cancelled || !autoplay) return@LaunchedEffect
        val found = (lookup as? PlaybackUpNextLookup.Found)?.upNext?.target
        when (lookup.stepAtEnd(pictureInPicture)) {
            MobileUpNextStep.Wait -> Unit
            MobileUpNextStep.Count -> found?.let { end.countDownTo(it) }
            MobileUpNextStep.PlayNow -> found?.let(end::play)
            MobileUpNextStep.Leave -> {
                end.cancelled = true
                currentOnPlaybackEnded()
            }
        }
    }
    return end
}

private suspend fun MobileUpNextEnd.countDownTo(target: PlaybackTarget) {
    countDownUpNext(remainingMillis) { remainingMillis = it }
    play(target)
}

internal fun MobileUpNextEnd.counting(lookup: PlaybackUpNextLookup, pictureInPicture: Boolean): PlaybackUpNext? =
    (lookup as? PlaybackUpNextLookup.Found)?.upNext
        ?.takeIf { ended && !cancelled && lookup.stepAtEnd(pictureInPicture) == MobileUpNextStep.Count }

@Composable
internal fun MobileUpNextCard(
    upNext: PlaybackUpNext?,
    remainingMillis: () -> Long,
    onPlayNow: (PlaybackTarget) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (upNext == null) return
    val seconds by remember { derivedStateOf { ceil(remainingMillis() / MILLIS_PER_SECOND).toLong().toString() } }
    val header = stringResource(R.string.mobile_playback_up_next_in, COUNTDOWN_SLOT)
    val mono = MaterialTheme.typography.bodySmall.putioMono()
    Row(
        modifier = modifier
            .widthIn(max = UP_NEXT_MAX_WIDTH)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
            .testTag(MOBILE_UP_NEXT_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = buildAnnotatedString {
                    append(header.substringBefore(COUNTDOWN_SLOT))
                    withStyle(
                        SpanStyle(
                            fontFamily = mono.fontFamily,
                            fontWeight = mono.fontWeight,
                            fontFeatureSettings = mono.fontFeatureSettings,
                        ),
                    ) { append(seconds) }
                    append(header.substringAfter(COUNTDOWN_SLOT))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MobileMiddleCutText(
                text = upNext.target.name,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(onClick = onCancel, colors = neutralTextButtonColors()) {
            Text(stringResource(R.string.mobile_action_cancel))
        }
        Button(
            onClick = { onPlayNow(upNext.target) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) { Text(stringResource(R.string.mobile_playback_play_now)) }
    }
}

private const val COUNTDOWN_SLOT = "\u0000"
private const val MILLIS_PER_SECOND = 1_000.0
private val UP_NEXT_MAX_WIDTH = 400.dp
