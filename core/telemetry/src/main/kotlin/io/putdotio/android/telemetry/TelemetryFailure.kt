package io.putdotio.android.telemetry

/**
 * A failure reported as one exception, [type] with [code] as its value, plus [tags]. A part that is
 * not a code of lowercase letters, digits and `_` is sent as `unknown`. That rejects error text,
 * URLs, paths and filenames but not a one-word name, so callers pass codes and ids only.
 */
public class TelemetryFailure(type: String, reasons: List<String>, tags: Map<String, String>) {
    internal val type: String = code(type)

    /** Such as `network, io_network_connection_failed`; Sentry groups failures by type and code. */
    public val code: String = reasons.joinToString(", ", transform = ::code)

    public val tags: Map<String, String> = tags.entries.associate { (key, value) -> code(key) to code(value) }
}

private val CODE = Regex("[a-z0-9_]{1,64}")

private fun code(value: String): String = value.takeIf(CODE::matches) ?: "unknown"
