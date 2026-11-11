package io.putdotio.android.tv

import androidx.annotation.StringRes
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.isNotFound

internal enum class TvFailureCopy(
    @StringRes val headline: Int,
    @StringRes val recovery: Int,
    @StringRes val line: Int,
) {
    Network(R.string.tv_state_offline_title, R.string.tv_error_network, R.string.tv_error_network),
    Timeout(R.string.tv_error_timeout_title, R.string.tv_error_timeout_recovery, R.string.tv_error_timeout),
    Session(R.string.tv_error_session_title, R.string.tv_error_session_recovery, R.string.tv_error_session),
    RateLimited(
        R.string.tv_error_rate_limited_title,
        R.string.tv_error_rate_limited_recovery,
        R.string.tv_error_rate_limited,
    ),
    NotFound(R.string.tv_error_not_found_title, R.string.tv_error_not_found_recovery, R.string.tv_error_not_found),
    Server(R.string.tv_error_server_title, R.string.tv_error_server_recovery, R.string.tv_error_unavailable),
}

internal fun PutioFailure.tvCopy(): TvFailureCopy? =
    when (this) {
        is PutioFailure.AuthenticationRequired -> TvFailureCopy.Session
        is PutioFailure.NetworkUnavailable -> TvFailureCopy.Network
        is PutioFailure.Timeout -> TvFailureCopy.Timeout
        is PutioFailure.RateLimited -> TvFailureCopy.RateLimited
        is PutioFailure.ApiRejected -> TvFailureCopy.NotFound.takeIf { isNotFound }
        is PutioFailure.ServerUnavailable,
        is PutioFailure.InvalidResponse,
        is PutioFailure.Unexpected,
        -> TvFailureCopy.Server
        is PutioFailure.AccessDenied,
        is PutioFailure.Misconfigured,
        -> null
    }
