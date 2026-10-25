package io.putdotio.android.playback

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.MobileErrorState
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioEmptyState
import io.putdotio.android.design.putioMono
import io.putdotio.sdk.files.PlaybackConversionState
import kotlin.math.roundToInt

/**
 * A video that fails takes the empty-state voice over the player. One that needs another player has
 * no action here: a download keeps the same HLS rendition. One a retry can fix offers Try again.
 * Audio keeps its own screen.
 */
@Composable
internal fun MobilePlaybackFailureState(
    title: String,
    failure: PlaybackFailure,
    onRetry: () -> Unit,
    video: Boolean = false,
) {
    val message = failure.apiReason ?: stringResource(failure.messageResource())
    if (!video) {
        if (failure.retryable) {
            MobileErrorState(
                title = title,
                message = message,
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = onRetry,
            )
        } else {
            MobileEmptyState(title = title, message = message)
        }
        return
    }
    val needsAnotherPlayer = failure is PlaybackFailure.MediaUnsupported
    MobilePlayerStateBox {
        PutioEmptyState(
            glyph = PutioEmptyGlyph.CantPlay,
            headline = if (needsAnotherPlayer) stringResource(R.string.mobile_playback_cant_play_title) else title,
            message = if (needsAnotherPlayer) stringResource(R.string.mobile_playback_cant_play_message) else message,
            action = if (failure.retryable) {
                { MobilePlayerStateAction(stringResource(R.string.mobile_action_retry), onClick = onRetry) }
            } else {
                null
            },
        )
    }
}

/**
 * Converting to MP4 over the player: a line that it keeps going, a bar with its percentage while it
 * runs, and the one action the state allows. Opening a video with no conversion requested starts
 * one; queued and running conversions poll. After a failure the viewer converts again, and checks
 * again where the status waits.
 */
@Composable
internal fun MobileConversionState(
    conversion: PlaybackContent.Conversion,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
) {
    PlaybackConversionPolling(conversion, onRefresh)
    val state = conversion.state
    val running = conversion.starting || state == PlaybackConversionState.Queued ||
        state is PlaybackConversionState.Converting
    val action = when (conversion.action) {
        PlaybackConversionAction.ConvertAgain -> R.string.mobile_playback_convert_again to onStart
        PlaybackConversionAction.CheckAgain -> R.string.mobile_playback_check_again to onRefresh
        null -> null
    }
    MobilePlayerStateBox {
        Column(
            Modifier.widthIn(max = CONVERSION_WIDTH).semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.mobile_playback_conversion_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (running) stringResource(R.string.mobile_playback_conversion_message) else state.message(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (running) {
                val percent = (state as? PlaybackConversionState.Converting)?.percent
                Spacer(Modifier.height(2.dp))
                if (percent == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.width(CONVERSION_BAR_WIDTH),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { (percent / PERCENT).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.width(CONVERSION_BAR_WIDTH),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Text(
                        "${percent.roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall.putioMono(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (action != null) {
                Spacer(Modifier.height(14.dp))
                MobilePlayerStateAction(
                    label = stringResource(action.first),
                    enabled = conversion.refreshRequestId == null,
                    onClick = action.second,
                )
            }
        }
    }
}

/** Centred on the player, clear of its top bar, scrolling when the window is short. */
@Composable
private fun MobilePlayerStateBox(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(vertical = 80.dp, horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The state's one tonal action. */
@Composable
private fun MobilePlayerStateAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(label) }
}

private val CONVERSION_WIDTH = 420.dp
private val CONVERSION_BAR_WIDTH = 320.dp
private const val PERCENT = 100.0

@Composable
private fun PlaybackConversionState.message(): String =
    when (this) {
        PlaybackConversionState.Queued,
        is PlaybackConversionState.Converting,
        -> stringResource(R.string.mobile_playback_conversion_message)
        PlaybackConversionState.Completed -> stringResource(R.string.mobile_playback_conversion_completed)
        PlaybackConversionState.Failed -> stringResource(R.string.mobile_playback_conversion_failed)
        PlaybackConversionState.NotAvailable -> stringResource(R.string.mobile_playback_conversion_unavailable)
        is PlaybackConversionState.Unknown -> stringResource(R.string.mobile_playback_conversion_unknown)
    }

@StringRes
private fun PlaybackFailure.messageResource(): Int =
    when (this) {
        is PlaybackFailure.MediaCredentialUnavailable -> R.string.mobile_playback_error_credential
        is PlaybackFailure.MediaUnsupported -> R.string.mobile_playback_error_media_unsupported
        is PlaybackFailure.Putio -> when (failure) {
            is PutioFailure.AuthenticationRequired -> R.string.mobile_state_error_session
            is PutioFailure.AccessDenied -> R.string.mobile_playback_error_forbidden
            is PutioFailure.RateLimited -> R.string.mobile_state_error_rate_limited
            is PutioFailure.NetworkUnavailable -> R.string.mobile_state_error_message
            is PutioFailure.ApiRejected,
            is PutioFailure.InvalidResponse,
            is PutioFailure.Misconfigured,
            is PutioFailure.ServerUnavailable,
            is PutioFailure.Unexpected,
            -> R.string.mobile_state_error_unavailable
        }
    }

@StringRes
internal fun PlaybackMediaType.loadingMessage(): Int =
    if (this == PlaybackMediaType.AUDIO) {
        R.string.mobile_playback_loading_audio
    } else {
        R.string.mobile_playback_loading
    }

@StringRes
internal fun PlaybackMediaType.errorTitle(): Int =
    if (this == PlaybackMediaType.AUDIO) {
        R.string.mobile_playback_error_title_audio
    } else {
        R.string.mobile_playback_error_title
    }
