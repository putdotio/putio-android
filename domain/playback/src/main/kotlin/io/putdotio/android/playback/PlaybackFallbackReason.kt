package io.putdotio.android.playback

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.PlaybackSourceKind
import java.net.HttpURLConnection

/** Why a video plays converted instead of as its original. */
public enum class PlaybackFallbackReason(
    public val code: String,
) {
    UserSwitched("user_switched"),
    PlaybackIssue("playback_issue"),
    NoMediaInfo("no_media_info"),
    CodecUnmapped("codec_unmapped"),
    VideoUnsupported("video_unsupported"),
    AudioUnsupported("audio_unsupported"),
    ContainerUnsupported("container_unsupported"),
    DecoderError("decoder_error"),
    CastReceiver("cast_receiver"),
}

/**
 * Whether the device plays these tracks: null when it does, or when there is no video track to
 * judge yet; otherwise why the original falls back.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public fun directPlayVerdict(tracks: Tracks): PlaybackFallbackReason? {
    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    return when {
        video.isEmpty() -> null
        video.none { it.isSupported(false) } -> PlaybackFallbackReason.VideoUnsupported
        audio.isNotEmpty() && audio.none { it.isSupported(false) } -> PlaybackFallbackReason.AudioUnsupported
        else -> null
    }
}

internal fun PlaybackState.originalRejected(event: PlaybackEvent.OriginalRejected): PlaybackTransition {
    if (!(content as? PlaybackContent.Ready).streamsOriginalVideo(target)) {
        return PlaybackTransition(this, consumed = false)
    }
    val requestId = PlaybackRequestId(nextRequestValue)
    val convertedTarget = target.convertedFor(event.reason)
    return PlaybackTransition(
        state = copy(
            target = convertedTarget,
            content = PlaybackContent.Loading(requestId),
            nextRequestValue = nextRequestValue + 1,
            resumePositionMillis = event.positionMillis.coerceAtLeast(0L),
            convertedOnly = true,
            linkRefetchedAtMillis = null,
        ),
        effect = PlaybackEffect.Resolve(convertedTarget, requestId, convertedOnly = true),
    )
}

private fun PlaybackTarget.convertedFor(reason: PlaybackFallbackReason): PlaybackTarget =
    if (reason == PlaybackFallbackReason.CastReceiver) copy(method = StreamingMethod.Hls) else this

private fun PlaybackContent.Ready?.streamsOriginalVideo(target: PlaybackTarget): Boolean =
    this != null && !offline && source.kind == PlaybackSourceKind.ORIGINAL &&
        target.mediaType == PlaybackMediaType.VIDEO

/** Why an original that failed before its first frame falls back; null for a failure converted would share. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public fun PlaybackException.originalRejection(): PlaybackFallbackReason? =
    when (errorCode) {
        in PARSING_ERROR_CODES -> PlaybackFallbackReason.ContainerUnsupported
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            PlaybackFallbackReason.ContainerUnsupported.takeIf { refusedAsBadRequest() }
        in FORMAT_ERROR_CODES -> when ((this as? ExoPlaybackException)?.rendererType()) {
            C.TRACK_TYPE_VIDEO -> PlaybackFallbackReason.VideoUnsupported
            C.TRACK_TYPE_AUDIO -> PlaybackFallbackReason.AudioUnsupported
            else -> PlaybackFallbackReason.DecoderError
        }
        in DECODER_ERROR_CODES -> PlaybackFallbackReason.DecoderError
        else -> null
    }

private fun PlaybackException.refusedAsBadRequest(): Boolean =
    cause.causeChain().responseStatus() == HttpURLConnection.HTTP_BAD_REQUEST

internal fun Throwable?.causeChain(): List<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSES).toList()

internal fun List<Throwable>.responseStatus(): Int? =
    firstNotNullOfOrNull { cause ->
        (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: cause.message?.relayedResponseCode()
    }

private const val MAX_CAUSES = 16

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun ExoPlaybackException.rendererType(): Int? {
    val mimeType = rendererFormat?.sampleMimeType?.takeIf { type == ExoPlaybackException.TYPE_RENDERER }
    return when {
        mimeType == null -> null
        MimeTypes.isVideo(mimeType) -> C.TRACK_TYPE_VIDEO
        MimeTypes.isAudio(mimeType) -> C.TRACK_TYPE_AUDIO
        else -> null
    }
}

private val PARSING_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
)

private val FORMAT_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
)

private val DECODER_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
)
