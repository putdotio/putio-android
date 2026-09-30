package io.putdotio.android.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackConversionAction
import io.putdotio.android.playback.PlaybackConversionPolling
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.action
import io.putdotio.android.playback.retryable
import io.putdotio.android.playback.startable
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.sdk.files.PlaybackConversionState
import kotlin.math.roundToInt

internal const val TV_CONVERSION_STATUS_TAG = "tv-player-conversion-status"

/**
 * The conversion-in-progress interstitial, laid out as tv-native's `VideoConversionStatus`
 * (putio-web `apps/tv-native` `features/files/components/video-conversion-status.tsx` @
 * `22264d5`): the file name, why it cannot play yet, then its conversion status. A queued or
 * running conversion polls ([PlaybackConversionPolling]), and playback starts on its own once
 * it resolves. The RN app started the conversion itself on opening; here the viewer does, with
 * the shared [PlaybackConversionAction].
 */
@Composable
internal fun TvConversionScreen(
    title: String,
    conversion: PlaybackContent.Conversion,
    onRefresh: () -> Unit,
    onStartConversion: () -> Unit,
) {
    val state = conversion.state
    val idle = conversion.refreshRequestId == null
    PlaybackConversionPolling(conversion, onRefresh)
    val action = when (conversion.action) {
        PlaybackConversionAction.Convert -> R.string.tv_player_convert to onStartConversion
        PlaybackConversionAction.ConvertAgain -> R.string.tv_player_convert_again to onStartConversion
        PlaybackConversionAction.CheckAgain -> R.string.tv_player_check_again to onRefresh
        null -> null
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(action?.first, idle) { if (action != null && idle) focus.requestFocus() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            text = stringResource(
                when {
                    state != PlaybackConversionState.NotAvailable -> R.string.tv_player_conversion_message
                    conversion.startable -> R.string.tv_player_conversion_start_message
                    else -> R.string.tv_player_conversion_unavailable_message
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.tv_player_conversion_status),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = state.label(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag(TV_CONVERSION_STATUS_TAG),
        )
        if (action != null) {
            TvButton(
                onClick = action.second,
                modifier = Modifier.padding(top = 24.dp).focusRequester(focus),
                enabled = idle,
            ) {
                Text(stringResource(action.first))
            }
        }
    }
}

@Composable
private fun PlaybackConversionState.label(): String =
    when (this) {
        PlaybackConversionState.Queued -> stringResource(R.string.tv_player_conversion_queued)
        // The RN app showed `percent_done || 0` followed by a percent sign.
        is PlaybackConversionState.Converting ->
            stringResource(R.string.tv_player_conversion_percent, percent?.roundToInt() ?: 0)
        PlaybackConversionState.Completed -> stringResource(R.string.tv_player_conversion_completed)
        PlaybackConversionState.Failed -> stringResource(R.string.tv_player_conversion_failed)
        PlaybackConversionState.NotAvailable -> stringResource(R.string.tv_player_conversion_not_available)
        is PlaybackConversionState.Unknown -> raw
    }

/**
 * A failed resolution or player error, by type, as tv-native's `localizeError` told network,
 * expired session, rate limit and server errors apart (putio-web `apps/tv-native`
 * `lib/errors/localize-error.ts` @ `22264d5`). Try again shows only where it can succeed; it
 * resolves the file again, which also replaces an expired media link, and keeps the position.
 * An expired session shows while the shell signs out.
 */
@Composable
internal fun TvPlaybackFailureScreen(failure: PlaybackFailure, onRetry: () -> Unit) {
    val (title, message) = failure.titleAndMessage()
    TvStatusScreen(
        title = stringResource(title),
        message = stringResource(message),
        action = if (failure.retryable) stringResource(R.string.tv_player_retry) else null,
        onAction = onRetry,
    )
}

/** String resources: the title, then the message. */
private fun PlaybackFailure.titleAndMessage(): Pair<Int, Int> =
    when (this) {
        is PlaybackFailure.AuthenticationRequired -> R.string.tv_player_error_title to R.string.tv_error_session
        is PlaybackFailure.NetworkUnavailable -> R.string.tv_player_error_title to R.string.tv_error_network
        is PlaybackFailure.MediaCredentialUnavailable ->
            R.string.tv_player_error_title to R.string.tv_player_error_link_expired
        is PlaybackFailure.RateLimited -> R.string.tv_player_error_title to R.string.tv_error_rate_limited
        is PlaybackFailure.AccessDenied -> R.string.tv_player_error_title to R.string.tv_player_error_forbidden
        is PlaybackFailure.MediaUnsupported ->
            R.string.tv_player_unsupported_title to R.string.tv_player_error_media_unsupported
        is PlaybackFailure.ApiRejected,
        is PlaybackFailure.Misconfigured,
        -> R.string.tv_player_error_title to R.string.tv_player_error_rejected
        is PlaybackFailure.ServerUnavailable,
        is PlaybackFailure.InvalidResponse,
        is PlaybackFailure.Unexpected,
        -> R.string.tv_player_error_title to R.string.tv_error_unavailable
    }
