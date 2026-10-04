package io.putdotio.android.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import io.putdotio.android.R
import kotlinx.coroutines.delay

internal const val MOBILE_SEEK_BACK_TAG = "mobile-seek-back"
internal const val MOBILE_SEEK_FORWARD_TAG = "mobile-seek-forward"
internal const val MOBILE_SEEK_FEEDBACK_TAG = "mobile-seek-feedback"
private const val MOBILE_SEEK_FEEDBACK_DELAY_MILLIS = 800L
internal const val MOBILE_SEEK_INTERVAL_MILLIS = 10_000L

internal enum class SeekDirection {
    Backward,
    Forward,
}

internal data class PendingSeek(
    val direction: SeekDirection,
    val targetPositionMillis: Long,
    val accumulatedMillis: Long,
    val requestId: Long,
    // Monotonic time until which the next request stacks on this target.
    val accumulatesUntilMillis: Long,
)

internal fun PendingSeek.accumulatesAt(nowMillis: Long): Boolean = nowMillis < accumulatesUntilMillis

internal data class PlayerSeekWindow(
    val available: Boolean,
    val durationMillis: Long,
)

internal fun pendingSeekAfterWindowUpdate(
    pending: PendingSeek?,
    previousWindow: PlayerSeekWindow,
    updatedWindow: PlayerSeekWindow,
): PendingSeek? =
    pending?.takeIf {
        updatedWindow.available &&
            updatedWindow.durationMillis == previousWindow.durationMillis &&
            it.targetPositionMillis <= updatedWindow.durationMillis
    }

internal fun Media3Player.currentSeekWindow(): PlayerSeekWindow {
    val canReadCurrentItem = isCommandAvailable(Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
    val knownDuration = if (canReadCurrentItem) duration else C.TIME_UNSET
    return playerSeekWindow(
        canReadCurrentItem = canReadCurrentItem,
        durationMillis = knownDuration,
        seekable = canReadCurrentItem && isCurrentMediaItemSeekable,
        live = canReadCurrentItem && isCurrentMediaItemLive,
        canSeek = isCommandAvailable(Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
    )
}

internal fun playerSeekWindow(
    canReadCurrentItem: Boolean,
    durationMillis: Long,
    seekable: Boolean,
    live: Boolean,
    canSeek: Boolean,
): PlayerSeekWindow {
    val knownDuration = durationMillis.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
    return PlayerSeekWindow(
        available =
            canReadCurrentItem &&
                knownDuration > 0L &&
                seekable &&
                !live &&
                canSeek,
        durationMillis = knownDuration,
    )
}

internal fun PlayerSeekWindow.nextPendingSeek(
    previous: PendingSeek?,
    currentPositionMillis: Long,
    direction: SeekDirection,
    requestId: Long,
    nowMillis: Long,
): PendingSeek? {
    if (durationMillis <= 0L) return null
    // The window is measured from the previous request, not from when its effect started.
    val stacked = previous?.takeIf { it.accumulatesAt(nowMillis) }
    val basePosition = (stacked?.targetPositionMillis ?: currentPositionMillis).coerceIn(0L, durationMillis)
    val targetPosition =
        when (direction) {
            SeekDirection.Backward -> (basePosition - MOBILE_SEEK_INTERVAL_MILLIS).coerceAtLeast(0L)
            SeekDirection.Forward ->
                if (durationMillis - basePosition <= MOBILE_SEEK_INTERVAL_MILLIS) {
                    durationMillis
                } else {
                    basePosition + MOBILE_SEEK_INTERVAL_MILLIS
                }
        }
    val movedMillis =
        when (direction) {
            SeekDirection.Backward -> basePosition - targetPosition
            SeekDirection.Forward -> targetPosition - basePosition
        }
    val accumulated =
        if (stacked?.direction == direction) {
            stacked.accumulatedMillis + movedMillis
        } else {
            movedMillis
        }
    return if (movedMillis == 0L) {
        null
    } else {
        PendingSeek(
            direction = direction,
            targetPositionMillis = targetPosition,
            accumulatedMillis = accumulated,
            requestId = requestId,
            accumulatesUntilMillis = nowMillis + MOBILE_SEEK_FEEDBACK_DELAY_MILLIS,
        )
    }
}

@Composable
internal fun SeekFeedbackTimeout(pendingSeek: MutableState<PendingSeek?>) {
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(pendingSeek.value?.requestId) {
        val requestId = pendingSeek.value?.requestId ?: return@LaunchedEffect
        val feedbackTimeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = MOBILE_SEEK_FEEDBACK_DELAY_MILLIS,
            containsText = true,
        ) ?: MOBILE_SEEK_FEEDBACK_DELAY_MILLIS
        // Accumulation expiry lives on the request; this timer only hides the feedback.
        delay(feedbackTimeout.coerceAtLeast(MOBILE_SEEK_FEEDBACK_DELAY_MILLIS))
        // A new seek can arrive before recomposition cancels the previous timer.
        if (pendingSeek.value?.requestId == requestId) pendingSeek.value = null
    }
}

@Composable
internal fun MobileSeekButton(
    direction: SeekDirection,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onVideo: Boolean = false,
) {
    val intervalSeconds = (MOBILE_SEEK_INTERVAL_MILLIS / 1_000L).toInt()
    val description =
        pluralStringResource(
            if (direction == SeekDirection.Backward) {
                R.plurals.mobile_playback_seek_back
            } else {
                R.plurals.mobile_playback_seek_forward
            },
            intervalSeconds,
            intervalSeconds,
        )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        ),
        modifier = modifier.requiredSize(if (onVideo) 48.dp else 56.dp).testTag(
            if (direction == SeekDirection.Backward) MOBILE_SEEK_BACK_TAG else MOBILE_SEEK_FORWARD_TAG,
        ).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.clearAndSetSemantics {}) {
            Icon(
                painter = painterResource(
                    if (direction == SeekDirection.Backward) {
                        R.drawable.ic_ph_arrow_counter_clockwise
                    } else {
                        R.drawable.ic_ph_arrow_clockwise
                    },
                ),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            // The number belongs to the fixed-size glyph; the accessible label carries the interval.
            Text("10", fontSize = with(LocalDensity.current) { 12.dp.toSp() })
        }
    }
}

@Composable
internal fun MobileSeekFeedback(
    request: PendingSeek,
    modifier: Modifier = Modifier,
) {
    val fractionalMovement = request.accumulatedMillis % 1_000L != 0L
    val seconds = (request.accumulatedMillis / 1_000L).toInt() + if (fractionalMovement) 1 else 0
    val message = when (request.direction) {
        SeekDirection.Backward -> if (fractionalMovement) {
            R.plurals.mobile_playback_seek_back_less_than
        } else {
            R.plurals.mobile_playback_seek_back
        }
        SeekDirection.Forward -> if (fractionalMovement) {
            R.plurals.mobile_playback_seek_forward_less_than
        } else {
            R.plurals.mobile_playback_seek_forward
        }
    }
    Text(
        text = pluralStringResource(message, seconds, seconds),
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.large,
                ).padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(MOBILE_SEEK_FEEDBACK_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
