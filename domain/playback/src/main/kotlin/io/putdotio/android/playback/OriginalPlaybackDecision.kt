package io.putdotio.android.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.putdotio.sdk.files.OriginalVideoDecision
import io.putdotio.sdk.files.PlaybackCapabilities
import io.putdotio.sdk.files.PlaybackRequest
import io.putdotio.sdk.files.PutioMediaInfo
import io.putdotio.sdk.files.PutioMediaStream

public enum class OriginalVideoPolicy {
    Never,
    Check,
    Always,
}

public fun interface DecoderInventory {
    public fun plays(format: Format): Boolean
}

internal class OriginalPlaybackDecision(
    private val decoders: DecoderInventory,
) {
    val originalVideoDecision: OriginalVideoDecision = OriginalVideoDecision { mediaInfo, _, _ ->
        runCatching { fallbackReason(mediaInfo) == null }.getOrDefault(false)
    }

    fun fallbackReason(mediaInfo: PutioMediaInfo?): PlaybackFallbackReason? {
        if (mediaInfo == null) return PlaybackFallbackReason.NoMediaInfo
        val streams = mediaInfo.streams.orEmpty()
        return when {
            !mediaInfo.playbackIssues.isNullOrEmpty() -> PlaybackFallbackReason.PlaybackIssue
            streams.isEmpty() -> PlaybackFallbackReason.NoMediaInfo
            !MediaInfoFormats.playableContainer(mediaInfo.format) -> PlaybackFallbackReason.ContainerUnsupported
            else -> streamReason(streams)
        }
    }

    private fun streamReason(streams: List<PutioMediaStream>): PlaybackFallbackReason? {
        val videoFormat = MediaInfoFormats.videoStreams(streams).firstOrNull()?.let(MediaInfoFormats::toFormat)
        val audio = MediaInfoFormats.audioStreams(streams)
        val audioFormats = audio.mapNotNull(MediaInfoFormats::toFormat)
        return when {
            videoFormat == null -> PlaybackFallbackReason.CodecUnmapped
            !decoders.plays(videoFormat) -> PlaybackFallbackReason.VideoUnsupported
            audio.isEmpty() -> null
            audioFormats.isEmpty() -> PlaybackFallbackReason.CodecUnmapped
            audioFormats.none(decoders::plays) -> PlaybackFallbackReason.AudioUnsupported
            else -> null
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public class MediaCodecDecoderInventory internal constructor(
    context: Context,
    private val softwareAudio: (String) -> Boolean,
) : DecoderInventory {
    public constructor(context: Context) : this(context, FfmpegLibrary::supportsFormat)

    private val context = context.applicationContext

    override fun plays(format: Format): Boolean {
        val mimeType = format.sampleMimeType ?: return false
        return mimeType == MimeTypes.AUDIO_RAW ||
            decodes(format) ||
            MimeTypes.isAudio(mimeType) && (passesThrough(format) || softwareAudio(mimeType))
    }

    private fun decodes(format: Format): Boolean =
        try {
            MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false)
                .any { it.isFormatSupported(context, format) }
        } catch (_: MediaCodecUtil.DecoderQueryException) {
            false
        }

    private fun passesThrough(format: Format): Boolean =
        AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
            .isPassthroughPlaybackSupported(format, AudioAttributes.DEFAULT)
}

internal fun PlaybackRequest.withOriginalVideo(
    policy: OriginalVideoPolicy,
    decision: OriginalPlaybackDecision,
): PlaybackRequest =
    copy(
        capabilities = if (capabilities.originalVideoPlayable) {
            policy.capabilities(decision)
        } else {
            PlaybackCapabilities()
        },
    )

internal fun OriginalVideoPolicy.capabilities(decision: OriginalPlaybackDecision): PlaybackCapabilities =
    when (this) {
        OriginalVideoPolicy.Check -> PlaybackCapabilities(originalVideoDecision = decision.originalVideoDecision)
        OriginalVideoPolicy.Always -> PlaybackCapabilities(originalVideoPlayable = true)
        OriginalVideoPolicy.Never -> PlaybackCapabilities()
    }
