package io.putdotio.android.telemetry

/**
 * The tags a reported failure may carry, named after putio-web's `playback_failure` contract so
 * one Sentry search covers every put.io player.
 */
public enum class TelemetryTag(internal val key: String) {
    TelemetryEvent("telemetry_event"),
    SchemaVersion("schema_version"),
    Player("player"),
    PlaybackOutcome("playback_outcome"),
    PlaybackTerminal("playback_terminal"),
    PlaybackFailureMode("playback_failure_mode"),
    EngineErrorCategory("engine_error_category"),
    EngineErrorCode("engine_error_code"),
    SourceKind("source_kind"),
    SourceRequestStatus("source_request_status"),
    MediaType("media_type"),
    FileId("file_id"),
}

/**
 * A failure an app reports, sent as one exception: [type], with [code] as its value, plus [tags].
 * Every part must be a code of lowercase letters, digits and `_`, or it is sent as `unknown`.
 * That shape rejects error text, URLs, paths and filenames but not a one-word name, so callers
 * pass codes and ids only.
 */
public class TelemetryFailure(
    type: String,
    reasons: List<String>,
    tags: Map<TelemetryTag, String>,
) {
    public val type: String = code(type)

    /** Such as `network, io_network_connection_failed`; Sentry groups failures by type and code. */
    public val code: String = reasons.joinToString(", ", transform = ::code)

    public val tags: Map<String, String> = tags.entries.associate { (tag, value) -> tag.key to code(value) }
}

private val CODE = Regex("[a-z0-9_]{1,64}")

private fun code(value: String): String = value.takeIf(CODE::matches) ?: "unknown"
