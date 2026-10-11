package io.putdotio.android.playback

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import io.putdotio.android.PutioFailure
import io.putdotio.android.telemetry.TelemetryFailure
import io.putdotio.android.telemetry.TelemetryTag
import io.putdotio.sdk.files.PlaybackSourceKind

/**
 * The diagnostics report for a player failure while this state plays: putio-web's
 * `playback_failure` contract (schema 1) in codes only. The exception's message and cause chain,
 * which can quote media URLs, never leave the device.
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
    val sourceKind = item?.localConfiguration?.let { local ->
        if (local.mimeType == MimeTypes.APPLICATION_M3U8) PlaybackSourceKind.HLS else PlaybackSourceKind.MP4
    }
    return playbackFailureTelemetry(error.toPlaybackFailure(), item?.mediaId?.toLongOrNull(), mediaType, sourceKind)
}

internal fun playbackFailureTelemetry(
    failure: PlaybackFailure,
    fileId: Long?,
    mediaType: PlaybackMediaType,
    sourceKind: PlaybackSourceKind?,
): TelemetryFailure {
    val causes = generateSequence(failure.cause) { it.cause }.take(MAX_CAUSES).toList()
    val exception = causes.filterIsInstance<PlaybackException>().firstOrNull()
    val status = causes.firstNotNullOfOrNull { cause ->
        (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: cause.message?.relayedResponseCode()
    }
    val mode = failureMode(failure, exception?.errorCode, status)
    val code = exception?.errorCodeName?.removePrefix("ERROR_CODE_")?.lowercase() ?: "none"
    val tags = buildMap {
        put(TelemetryTag.TelemetryEvent, EVENT)
        put(TelemetryTag.SchemaVersion, "1")
        put(TelemetryTag.Player, "media3")
        put(TelemetryTag.PlaybackOutcome, "failed")
        put(TelemetryTag.PlaybackTerminal, "true")
        put(TelemetryTag.PlaybackFailureMode, mode)
        put(TelemetryTag.EngineErrorCategory, exception?.errorCode?.let(::errorCategory) ?: "none")
        put(TelemetryTag.EngineErrorCode, code)
        put(TelemetryTag.SourceKind, sourceKind.tagValue())
        status?.let { put(TelemetryTag.SourceRequestStatus, it.toString()) }
        put(TelemetryTag.MediaType, mediaType.name.lowercase())
        fileId?.let { put(TelemetryTag.FileId, it.toString()) }
    }
    return TelemetryFailure(EVENT, listOf(mode, code), tags)
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
        failure.putioFailure is PutioFailure.NetworkUnavailable -> "network"
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
        PlaybackSourceKind.MP4, PlaybackSourceKind.ORIGINAL -> "direct"
        null -> "unknown"
    }

private const val EVENT = "playback_failure"
private const val MAX_CAUSES = 16
