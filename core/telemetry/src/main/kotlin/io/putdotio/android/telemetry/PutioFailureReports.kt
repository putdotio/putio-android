package io.putdotio.android.telemetry

import io.putdotio.android.PutioFailure
import java.util.WeakHashMap

public object PutioFailureReports {
    private val ids = WeakHashMap<PutioFailure, String>()

    public fun reported(failure: PutioFailure): String? = synchronized(ids) { ids[failure] }

    public fun idFor(failure: PutioFailure): String? =
        if (!failure.isUnknown) {
            null
        } else {
            synchronized(ids) {
                ids[failure] ?: PutioDiagnostics.capture(failure.telemetry())?.also { ids[failure] = it }
            }
        }

    public fun record(failure: PutioFailure, id: String?) {
        if (id != null && failure.isUnknown) synchronized(ids) { ids[failure] = id }
    }
}

public val PutioFailure.isUnknown: Boolean
    get() = this is PutioFailure.Unexpected || this is PutioFailure.InvalidResponse

private fun PutioFailure.telemetry(): TelemetryFailure {
    val kind = if (this is PutioFailure.InvalidResponse) "invalid_response" else "unexpected"
    return TelemetryFailure(
        type = "putio_failure",
        reasons = listOf(kind),
        tags = mapOf("failure_kind" to kind),
    )
}
