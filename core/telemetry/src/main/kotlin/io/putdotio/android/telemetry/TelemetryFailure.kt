package io.putdotio.android.telemetry

public enum class TelemetrySeverity {
    Error,
    Warning,
}

/**
 * A failure reported as one exception, [type] with [code] as its value, plus [tags]. A key or
 * reason that is not a code of lowercase letters, digits and `_` is sent as `unknown`; a tag value
 * may also join codes with single spaces, or words with `-`, as media facts such as `main 10` or
 * `mpeg-ts` do.
 * That rejects error text, URLs, paths and filenames but not a few plain words, so callers pass
 * codes, ids and put.io's own media facts only.
 *
 * A [shared] report leaves as put.io's cross-app event instead.
 */
public class TelemetryFailure(
    type: String,
    reasons: List<String>,
    tags: Map<String, String>,
    public val severity: TelemetrySeverity = TelemetrySeverity.Error,
    public val shared: SharedTelemetry? = null,
) {
    internal val type: String = code(type)

    /** Such as `network, io_network_connection_failed`; Sentry groups failures by type and code. */
    public val code: String = reasons.joinToString(", ", transform = ::code)

    public val tags: Map<String, String> = tags.entries.associate { (key, value) -> code(key) to value(value) }

}

/**
 * A report every put.io app sends alike: [message], such as `playback failed: decode`, replaces the
 * exception, [fingerprint] groups it instead of Sentry's own grouping, and it carries no user and
 * no tags beyond its own.
 */
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
