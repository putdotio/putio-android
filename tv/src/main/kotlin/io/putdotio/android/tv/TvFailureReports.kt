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
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.TelemetryFailure
import java.util.WeakHashMap

internal object TvFailureReports {
    private val ids = WeakHashMap<PutioFailure, String>()

    fun reported(failure: PutioFailure): String? = synchronized(ids) { ids[failure] }

    fun idFor(failure: PutioFailure): String? =
        synchronized(ids) {
            ids[failure] ?: PutioDiagnostics.capture(failure.telemetry())?.also { ids[failure] = it }
        }

    fun record(failure: PutioFailure, id: String?) {
        if (id != null) synchronized(ids) { ids[failure] = id }
    }
}

internal val PutioFailure.isUnknown: Boolean
    get() = this is PutioFailure.Unexpected || this is PutioFailure.InvalidResponse

@Composable
internal fun PutioFailure.tvErrorId(): String? {
    val failure = this
    var id by remember(failure) { mutableStateOf(TvFailureReports.reported(failure)) }
    LaunchedEffect(failure) { if (id == null) id = TvFailureReports.idFor(failure) }
    return id
}

@Composable
internal fun PutioFailure.tvUnknownLine(): String =
    tvErrorId()?.let { stringResource(R.string.tv_error_unexpected_with_id, it) }
        ?: stringResource(R.string.tv_error_unexpected)

private fun PutioFailure.telemetry(): TelemetryFailure {
    val kind = if (this is PutioFailure.InvalidResponse) "invalid_response" else "unexpected"
    return TelemetryFailure(
        type = "putio_failure",
        reasons = listOf(kind),
        tags = mapOf("failure_kind" to kind),
    )
}
