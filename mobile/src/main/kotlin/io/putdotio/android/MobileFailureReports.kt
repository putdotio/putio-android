package io.putdotio.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.putdotio.android.telemetry.PutioFailureReports
import io.putdotio.android.telemetry.isUnknown

@Composable
internal fun PutioFailure.mobileErrorId(): String? {
    if (!isUnknown) return null
    val failure = this
    var id by remember(failure) { mutableStateOf(PutioFailureReports.reported(failure)) }
    LaunchedEffect(failure) { if (id == null) id = PutioFailureReports.idFor(failure) }
    return id
}

@Composable
internal fun PutioFailure.mobileUnknownMessage(fallback: String): String =
    mobileErrorId()?.let { stringResource(R.string.mobile_state_error_with_id, fallback, it) } ?: fallback

@Composable
internal fun PutioFailure.mobileReportedMessage(fallback: String): String {
    val id = remember(this) { if (isUnknown) PutioFailureReports.reported(this) else null }
    return id?.let { stringResource(R.string.mobile_state_error_with_id, fallback, it) } ?: fallback
}

internal fun PutioFailure.mobileUnknownMessage(context: Context, fallback: String): String =
    PutioFailureReports.idFor(this)
        ?.let { context.getString(R.string.mobile_state_error_with_id, fallback, it) }
        ?: fallback
