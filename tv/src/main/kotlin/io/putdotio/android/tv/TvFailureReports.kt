package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.TelemetryFailure
import java.util.WeakHashMap

internal object TvFailureReports {
    private val ids = WeakHashMap<PutioFailure, String?>()

    fun idFor(failure: PutioFailure): String? =
        synchronized(ids) {
            if (ids.containsKey(failure)) {
                ids[failure]
            } else {
                PutioDiagnostics.capture(failure.telemetry()).also { ids[failure] = it }
            }
        }

    fun record(failure: PutioFailure, id: String?) {
        synchronized(ids) { ids[failure] = id }
    }
}

internal val PutioFailure.isUnknown: Boolean
    get() = this is PutioFailure.Unexpected || this is PutioFailure.InvalidResponse

@Composable
internal fun PutioFailure.tvErrorId(): String? = remember(this) { TvFailureReports.idFor(this) }

@Composable
internal fun PutioFailure.tvUnknownLine(): String =
    tvErrorId()?.let { stringResource(R.string.tv_error_unexpected_with_id, it) }
        ?: stringResource(R.string.tv_error_unexpected)

private fun PutioFailure.telemetry(): TelemetryFailure {
    val kind = if (this is PutioFailure.InvalidResponse) "invalid_response" else "unexpected"
    return TelemetryFailure(
        type = "putio_failure",
        reasons = listOf(kind, cause.javaClass.simpleName.toCode()),
        tags = mapOf("failure_kind" to kind),
    )
}

private fun String.toCode(): String = CAMEL_BOUNDARY.replace(this, "$1_$2").lowercase()

private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
