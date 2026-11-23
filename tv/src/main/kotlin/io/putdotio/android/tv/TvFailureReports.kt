package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.telemetry.PutioFailureReports
import io.putdotio.android.telemetry.isUnknown as isUnknownFailure

internal val PutioFailure.isUnknown: Boolean
    get() = isUnknownFailure

@Composable
internal fun PutioFailure.tvErrorId(): String? {
    val failure = this
    if (!failure.isUnknown) return null
    var id by remember(failure) { mutableStateOf(PutioFailureReports.reported(failure)) }
    LaunchedEffect(failure) { if (id == null) id = PutioFailureReports.idFor(failure) }
    return id
}

@Composable
internal fun PutioFailure.tvUnknownLine(): String =
    tvErrorId()?.let { stringResource(R.string.tv_error_unexpected_with_id, it) }
        ?: stringResource(R.string.tv_error_unexpected)
