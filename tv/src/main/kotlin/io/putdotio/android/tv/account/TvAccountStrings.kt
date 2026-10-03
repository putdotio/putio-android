package io.putdotio.android.tv.account

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AndroidAppConfigFailure
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.settings.apiReason

/** put.io's reason for a refused request, else this failure's copy. */
@Composable
internal fun AccountSettingsFailure.tvMessageText(): String = apiReason ?: stringResource(tvMessage())

@StringRes
internal fun AccountSettingsFailure.tvMessage(): Int =
    when (this) {
        is AccountSettingsFailure.AuthenticationRequired -> R.string.tv_error_session
        is AccountSettingsFailure.AccessDenied -> R.string.tv_account_error_access_denied
        is AccountSettingsFailure.RouteUnavailable -> R.string.tv_account_error_route_unavailable
        is AccountSettingsFailure.RateLimited -> R.string.tv_error_rate_limited
        is AccountSettingsFailure.NetworkUnavailable -> R.string.tv_error_network
        is AccountSettingsFailure.ServerUnavailable,
        is AccountSettingsFailure.ApiRejected,
        is AccountSettingsFailure.InvalidResponse,
        is AccountSettingsFailure.Misconfigured,
        is AccountSettingsFailure.Unexpected,
        -> R.string.tv_error_unavailable
    }

/** put.io's reason for a refused request, else this failure's copy. */
@Composable
internal fun AndroidAppConfigFailure.tvMessageText(): String = apiReason ?: stringResource(tvMessage())

@StringRes
internal fun AndroidAppConfigFailure.tvMessage(): Int =
    when (this) {
        is AndroidAppConfigFailure.AuthenticationRequired -> R.string.tv_error_session
        is AndroidAppConfigFailure.AccessDenied -> R.string.tv_account_error_access_denied
        is AndroidAppConfigFailure.RateLimited -> R.string.tv_error_rate_limited
        is AndroidAppConfigFailure.NetworkUnavailable -> R.string.tv_error_network
        is AndroidAppConfigFailure.ServerUnavailable,
        is AndroidAppConfigFailure.ApiRejected,
        is AndroidAppConfigFailure.InvalidResponse,
        is AndroidAppConfigFailure.Misconfigured,
        is AndroidAppConfigFailure.Unexpected,
        -> R.string.tv_error_unavailable
    }

@StringRes
internal fun VideoPlaybackType.tvLabel(): Int =
    when (this) {
        VideoPlaybackType.Hls -> R.string.tv_account_video_playback_hls
        VideoPlaybackType.Mp4 -> R.string.tv_account_video_playback_mp4
    }
