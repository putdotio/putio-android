package io.putdotio.android.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.putdotio.android.PutioFailure
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.TelemetryFailure
import io.putdotio.android.telemetry.TelemetrySeverity
import io.putdotio.sdk.files.PlaybackSourceKind

/**
 * A player failure as putio-web's `playback_failure` report (schema 1), in codes only: the
 * exception's message and cause chain can quote media URLs.
 */
public fun PlaybackState.playerFailureTelemetry(failure: PlaybackFailure): TelemetryFailure =
    playbackFailureTelemetry(
        failure = failure,
        fileId = target.fileId.value,
        mediaType = target.mediaType,
        sourceKind = (content as? PlaybackContent.Ready)?.source?.kind,
    )

/** The same report for a player no [PlaybackState] follows, such as the background audio service's. */
public fun Player.playerFailureTelemetry(error: PlaybackException, mediaType: PlaybackMediaType): TelemetryFailure {
    val item = currentMediaItem
    val fileId = item?.mediaId?.toLongOrNull()
    return playbackFailureTelemetry(error.toPlaybackFailure(), fileId, mediaType, item.sourceKind())
}

/**
 * A rejected original as a non-terminal report in the same schema: the converted stream takes
 * over, so only the reason's code goes, as a warning. A viewer's own switch and Cast's Standard
 * rescue nothing, so they report nothing.
 */
public fun PlaybackState.originalFallbackTelemetry(reason: PlaybackFallbackReason): TelemetryFailure? {
    if (reason == PlaybackFallbackReason.UserSwitched || reason == PlaybackFallbackReason.CastReceiver) return null
    val tags = mapOf(
        "telemetry_event" to EVENT,
        "schema_version" to "1",
        "player" to "media3",
        "playback_outcome" to "fallback",
        "playback_terminal" to "false",
        "playback_fallback_reason" to reason.code,
        "source_kind" to PlaybackSourceKind.ORIGINAL.tagValue(),
        "media_type" to target.mediaType.name.lowercase(),
        "file_id" to "${target.fileId.value}",
    )
    return TelemetryFailure(EVENT, listOf("fallback", reason.code), tags, TelemetrySeverity.Warning)
}

private fun playbackFailureTelemetry(
    failure: PlaybackFailure,
    fileId: Long?,
    mediaType: PlaybackMediaType,
    sourceKind: PlaybackSourceKind?,
): TelemetryFailure {
    val (exception, status) = failure.engineFacts()
    val mode = failureMode(failure, exception?.errorCode, status)
    val code = exception?.errorCodeName?.removePrefix("ERROR_CODE_")?.lowercase() ?: "none"
    val tags = mapOf(
        "telemetry_event" to EVENT,
        "schema_version" to "1",
        "player" to "media3",
        "playback_outcome" to "failed",
        "playback_terminal" to "true",
        "playback_failure_mode" to mode,
        "engine_error_category" to (exception?.errorCode?.let(::errorCategory) ?: "none"),
        "engine_error_code" to code,
        "source_kind" to sourceKind.tagValue(),
        "media_type" to mediaType.name.lowercase(),
    ) + listOfNotNull(status?.let { "source_request_status" to "$it" }, fileId?.let { "file_id" to "$it" })
    return TelemetryFailure(EVENT, listOf(mode, code), tags)
}

private fun PlaybackFailure.engineFacts(): Pair<PlaybackException?, Int?> {
    val causes = cause.causeChain()
    return causes.filterIsInstance<PlaybackException>().firstOrNull() to causes.responseStatus()
}

/** The failure as product analytics' `error`: `source` is a deleted, unpaid or throttled file. */
internal fun PlaybackFailure.analyticsError(): AnalyticsEvent.PlaybackError {
    val (exception, status) = engineFacts()
    return when (failureMode(this, exception?.errorCode, status)) {
        "source_gone", "entitlement", "throttled" -> AnalyticsEvent.PlaybackError.Source
        "server_transient", "network", "load" -> AnalyticsEvent.PlaybackError.Network
        "unsupported_source" -> AnalyticsEvent.PlaybackError.Unsupported
        "decode", "drm" -> AnalyticsEvent.PlaybackError.Media
        else -> AnalyticsEvent.PlaybackError.Unknown
    }
}

// The web contract's failure modes; a definitive source status outranks the player's own code.
@Suppress("CyclomaticComplexMethod", "MagicNumber")
private fun failureMode(failure: PlaybackFailure, code: Int?, status: Int?): String =
    when {
        status == 404 || status == 410 -> "source_gone"
        status == 402 -> "entitlement"
        status == 429 -> "throttled"
        status != null && (status == 408 || status >= 500) -> "server_transient"
        failure is PlaybackFailure.MediaUnsupported -> "unsupported_source"
        failure.putioFailure is PutioFailure.NetworkUnavailable ||
            failure.putioFailure is PutioFailure.Timeout -> "network"
        code == null -> "unknown"
        code / 1000 == 2 -> "load"
        code / 1000 == 3 || code / 1000 == 4 -> "decode"
        code / 1000 == 6 -> "drm"
        else -> "unknown"
    }

// Media3 groups its error codes by thousands.
@Suppress("MagicNumber")
private fun errorCategory(code: Int): String =
    when (code / 1000) {
        1 -> "misc"
        2 -> "io"
        3 -> "parsing"
        4 -> "decoding"
        5 -> "audio_track"
        6 -> "drm"
        7 -> "video_frame_processing"
        else -> "other"
    }

private fun PlaybackSourceKind?.tagValue(): String =
    when (this) {
        PlaybackSourceKind.HLS -> "hls"
        PlaybackSourceKind.MP4 -> "direct"
        PlaybackSourceKind.ORIGINAL -> "original"
        null -> "unknown"
    }

private const val EVENT = "playback_failure"
