package io.putdotio.android.telemetry

public enum class TelemetrySeverity {
    Error,
    Warning,
}

public class TelemetryFailure(
    type: String,
    reasons: List<String>,
    tags: Map<String, String>,
    public val severity: TelemetrySeverity = TelemetrySeverity.Error,
    public val shared: SharedTelemetry? = null,
) {
    internal val type: String = code(type)

    public val code: String = reasons.joinToString(", ", transform = ::code)

    public val tags: Map<String, String> = tags.entries.associate { (key, value) -> code(key) to value(value) }

}

public class SharedTelemetry(
    message: String,
    fingerprint: List<String>,
) {
    public val message: String = message.takeIf(MESSAGE::matches) ?: "unknown"

    public val fingerprint: List<String> = fingerprint.map(::code)
}

private val CODE = Regex("[a-z0-9_]{1,64}")
private val VALUE = Regex("(?=.{1,64}$)[a-z0-9_]+(?:(?: |(?<=[a-z])-(?=[a-z]))[a-z0-9_]+)*")
private val MESSAGE = Regex("(?=.{1,128}$)[a-z0-9_]+(?: [a-z0-9_]+)*(?:: [a-z0-9_]+)*")

private fun code(value: String): String = value.takeIf(CODE::matches) ?: "unknown"

private fun value(value: String): String = value.takeIf(VALUE::matches) ?: "unknown"
