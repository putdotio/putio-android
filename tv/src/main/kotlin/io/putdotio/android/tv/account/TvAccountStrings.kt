package io.putdotio.android.tv.account

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.apiReason
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.settings.apiReason

/** put.io's reason for a refused request, else this failure's copy. */
@Composable
internal fun AccountSettingsFailure.tvMessageText(): String = apiReason ?: stringResource(tvMessage())

@StringRes
internal fun AccountSettingsFailure.tvMessage(): Int =
    when (this) {
        is AccountSettingsFailure.RouteUnavailable -> R.string.tv_account_error_route_unavailable
        is AccountSettingsFailure.Putio -> when (failure) {
            is PutioFailure.AuthenticationRequired -> R.string.tv_error_session
            is PutioFailure.AccessDenied -> R.string.tv_account_error_access_denied
            is PutioFailure.RateLimited -> R.string.tv_error_rate_limited
            is PutioFailure.NetworkUnavailable -> R.string.tv_error_network
            is PutioFailure.ServerUnavailable,
            is PutioFailure.ApiRejected,
            is PutioFailure.InvalidResponse,
            is PutioFailure.Misconfigured,
            is PutioFailure.Unexpected,
            -> R.string.tv_error_unavailable
        }
    }

/** put.io's reason for a refused request, else this failure's copy. */
@Composable
internal fun PutioFailure.tvAppConfigMessageText(): String = apiReason ?: stringResource(tvAppConfigMessage())

@StringRes
internal fun PutioFailure.tvAppConfigMessage(): Int =
    when (this) {
        is PutioFailure.AuthenticationRequired -> R.string.tv_error_session
        is PutioFailure.AccessDenied -> R.string.tv_account_error_access_denied
        is PutioFailure.RateLimited -> R.string.tv_error_rate_limited
        is PutioFailure.NetworkUnavailable -> R.string.tv_error_network
        is PutioFailure.ServerUnavailable,
        is PutioFailure.ApiRejected,
        is PutioFailure.InvalidResponse,
        is PutioFailure.Misconfigured,
        is PutioFailure.Unexpected,
        -> R.string.tv_error_unavailable
    }

@StringRes
internal fun VideoPlaybackType.tvLabel(): Int =
    when (this) {
        VideoPlaybackType.Hls -> R.string.tv_account_video_playback_hls
        VideoPlaybackType.Mp4 -> R.string.tv_account_video_playback_mp4
    }
