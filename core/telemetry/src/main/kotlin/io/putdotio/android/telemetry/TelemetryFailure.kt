package io.putdotio.android.telemetry

/**
 * The tags a reported failure may carry, named after putio-web's `playback_failure` contract so
 * one Sentry search covers every put.io player. The redaction boundary drops every other tag.
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
 * A failure an app reports, reduced to identifiers: lowercase letters, digits, `_`, `.` and
 * `-`, at most 64 of them. Anything else becomes `unknown`, so error text, URLs, paths,
 * filenames and media titles cannot get here. Events group by [event] and [reasons], never
 * by message text.
 */
public class TelemetryFailure(
    event: String,
    reasons: List<String>,
    tags: Map<TelemetryTag, String>,
) {
    public val fingerprint: List<String> = (listOf(event) + reasons).map(::identifier)

    /** The issue title, such as `playback_failure (network, io_network_connection_failed)`. */
    public val message: String =
        fingerprint.first() + if (reasons.isEmpty()) "" else fingerprint.drop(1).joinToString(", ", " (", ")")

    public val tags: Map<String, String> = tags.entries.associate { (tag, value) -> tag.key to identifier(value) }
}

private val IDENTIFIER = Regex("[a-z0-9_.-]{1,64}")

private fun identifier(value: String): String = value.lowercase().takeIf(IDENTIFIER::matches) ?: "unknown"
