package io.putdotio.android.tv.account

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.apiReason
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.android.settings.apiReason
import io.putdotio.android.tv.TvFailureCopy
import io.putdotio.android.tv.tvCopy

/** put.io's reason for a refused request, else this failure's copy. */
@Composable
internal fun AccountSettingsFailure.tvMessageText(): String = apiReason ?: stringResource(tvMessage())

@StringRes
internal fun AccountSettingsFailure.tvMessage(): Int =
    when (this) {
        is AccountSettingsFailure.RouteUnavailable -> R.string.tv_account_error_route_unavailable
        is AccountSettingsFailure.Putio -> failure.tvAppConfigMessage()
    }

/** put.io's reason for a refused request, else this failure's copy. */
@Composable
internal fun PutioFailure.tvAppConfigMessageText(): String = apiReason ?: stringResource(tvAppConfigMessage())

@StringRes
internal fun PutioFailure.tvAppConfigMessage(): Int =
    tvCopy()?.line ?: if (this is PutioFailure.AccessDenied) {
        R.string.tv_account_error_access_denied
    } else {
        TvFailureCopy.Server.line
    }

@StringRes
internal fun StreamingMethod.tvLabel(): Int =
    when (this) {
        StreamingMethod.Hls -> R.string.tv_account_playback_standard
        StreamingMethod.File -> R.string.tv_account_playback_direct
    }

@StringRes
internal fun StreamingMethod.tvDescription(): Int =
    when (this) {
        StreamingMethod.Hls -> R.string.tv_account_playback_standard_description
        StreamingMethod.File -> R.string.tv_account_playback_direct_description
    }

@Composable
internal fun tvMonoArguments(@StringRes template: Int, vararg values: String): AnnotatedString {
    val text = stringResource(template, *values)
    val mono = TvAccountType.mono.toSpanStyle()
    return buildAnnotatedString {
        append(text)
        var from = 0
        values.forEach { value ->
            val start = text.indexOf(value, from)
            if (start >= 0) {
                addStyle(mono, start, start + value.length)
                from = start + value.length
            }
        }
    }
}
