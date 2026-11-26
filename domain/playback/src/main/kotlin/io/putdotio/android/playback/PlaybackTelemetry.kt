package io.putdotio.android.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.putdotio.android.PutioFailure
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.SharedTelemetry
import io.putdotio.android.telemetry.TelemetryFailure
import io.putdotio.android.telemetry.TelemetrySeverity
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackStreamFormat
import java.util.Locale

public fun PlaybackState.playerFailureTelemetry(
    failure: PlaybackFailure,
    platform: PlaybackPlatform,
    firstFrameSource: PlaybackSource?,
): TelemetryFailure {
    val ready = content as? PlaybackContent.Ready
    val offline = ready?.offline == true
    val played = PlayedSource(target.fileId.value, target.mediaType, ready?.source, offline, target.method)
    val started = ready != null && ready.source === firstFrameSource
    return playbackFailureTelemetry(failure, platform, played, started)
}

public val PlaybackState.readySource: PlaybackSource?
    get() = (content as? PlaybackContent.Ready)?.source

public fun Player.playerFailureTelemetry(
    error: PlaybackException,
    mediaType: PlaybackMediaType,
    platform: PlaybackPlatform,
    started: Boolean,
): TelemetryFailure {
    val item = currentMediaItem
    val played = PlayedSource(item?.mediaId?.toLongOrNull(), mediaType, null, item.offline(), null, item.sourceKind())
    return playbackFailureTelemetry(error.toPlaybackFailure(), platform, played, started)
}

public fun PlaybackState.originalFallbackTelemetry(
    reason: PlaybackFallbackReason,
    platform: PlaybackPlatform,
): TelemetryFailure? {
    if (reason == PlaybackFallbackReason.UserSwitched || reason == PlaybackFallbackReason.CastReceiver) return null
    val ready = content as? PlaybackContent.Ready
    val played = PlayedSource(
        target.fileId.value,
        target.mediaType,
        ready?.source,
        ready?.offline == true,
        target.method,
        PlaybackSourceKind.ORIGINAL,
    )
    val code = reason.rescueReason
    val tags = played.tags(platform, RESCUED, started = false) + mapOf(
        "failure_mode" to reason.failureMode,
        "rescue_reason" to code,
        "rescue_to" to "converted",
        "engine_error_domain" to NONE,
        "engine_error_category" to UNKNOWN,
        "engine_error_code" to UNKNOWN,
        "source_request_status" to UNKNOWN,
    )
    return report(platform, RESCUED, code, UNKNOWN, tags, TelemetrySeverity.Warning)
}

private fun playbackFailureTelemetry(
    failure: PlaybackFailure,
    platform: PlaybackPlatform,
    played: PlayedSource,
    started: Boolean,
): TelemetryFailure {
    val (exception, status) = failure.engineFacts()
    val mode = failureMode(failure, exception?.errorCode, status)
    val code = exception?.errorCodeName?.removePrefix("ERROR_CODE_")?.lowercase(Locale.ROOT) ?: NONE
    val tags = played.tags(platform, FAILED, started) + mapOf(
        "failure_mode" to mode,
        "rescue_reason" to NONE,
        "rescue_to" to NONE,
        "engine_error_domain" to NONE,
        "engine_error_category" to (exception?.errorCode?.let(::errorCategory) ?: NONE),
        "engine_error_code" to code,
        "source_request_status" to (status?.toString() ?: NONE),
    )
    return report(platform, FAILED, mode, code, tags, TelemetrySeverity.Error)
}

@Suppress("LongParameterList")
private fun report(
    platform: PlaybackPlatform,
    outcome: String,
    reason: String,
    engineCode: String,
    tags: Map<String, String>,
    severity: TelemetrySeverity,
): TelemetryFailure =
    TelemetryFailure(
        type = EVENT,
        reasons = listOf(reason, engineCode),
        tags = tags,
        severity = severity,
        shared = SharedTelemetry(
            message = "playback $outcome: $reason",
            fingerprint = listOf(platform.tag, outcome, reason, engineCode),
        ),
    )

private class PlayedSource(
    val fileId: Long?,
    val mediaType: PlaybackMediaType,
    val source: PlaybackSource?,
    val offline: Boolean,
    val method: StreamingMethod?,
    val kind: PlaybackSourceKind? = source?.kind,
) {
    fun tags(platform: PlaybackPlatform, outcome: String, started: Boolean): Map<String, String> =
        mapOf(
            "telemetry_event" to EVENT,
            "schema_version" to SCHEMA_VERSION,
            "platform" to platform.tag,
            "player" to "media3",
            "playback_outcome" to outcome,
            "playback_terminal" to "${outcome == FAILED}",
            "playback_started" to "$started",
            "streaming_method" to streamingMethod(),
            "source_kind" to sourceKind(),
            "device_check" to deviceCheck(),
            "file_id" to (fileId?.toString() ?: UNKNOWN),
        ) + mediaFacts(source?.mediaInfo)

    private fun streamingMethod(): String =
        method?.wireValue ?: when {
            source == null || offline || mediaType == PlaybackMediaType.AUDIO -> UNKNOWN
            source.format == PlaybackStreamFormat.HLS -> StreamingMethod.Hls.wireValue
            else -> StreamingMethod.File.wireValue
        }

    private fun sourceKind(): String =
        when {
            offline -> "local_file"
            kind == PlaybackSourceKind.HLS -> "hls"
            kind == PlaybackSourceKind.MP4 -> "converted"
            kind == PlaybackSourceKind.ORIGINAL -> "original"
            else -> UNKNOWN
        }

    private fun deviceCheck(): String =
        when {
            kind == null -> UNKNOWN
            !offline && mediaType == PlaybackMediaType.VIDEO && kind == PlaybackSourceKind.ORIGINAL -> "pass"
            else -> "skipped"
        }
}

private val PlaybackFallbackReason.rescueReason: String
    get() = if (this == PlaybackFallbackReason.NoMediaInfo) "not_ready" else code

private val PlaybackFallbackReason.failureMode: String
    get() = if (this == PlaybackFallbackReason.DecoderError) "decode" else "unsupported_source"

private fun PlaybackFailure.engineFacts(): Pair<PlaybackException?, Int?> {
    val causes = cause.causeChain()
    return causes.filterIsInstance<PlaybackException>().firstOrNull() to causes.responseStatus()
}

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

// The report's failure modes; a definitive source status outranks the player's own code.
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
        code == null -> UNKNOWN
        code / 1000 == 2 -> "load"
        code / 1000 == 3 || code / 1000 == 4 -> "decode"
        code / 1000 == 6 -> "drm"
        else -> UNKNOWN
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

private const val EVENT = "playback_failure"
private const val SCHEMA_VERSION = "2"
private const val FAILED = "failed"
private const val RESCUED = "rescued"
private const val NONE = "none"
private const val UNKNOWN = "unknown"
