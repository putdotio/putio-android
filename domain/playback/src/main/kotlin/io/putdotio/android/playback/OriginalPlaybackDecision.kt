package io.putdotio.android.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.putdotio.sdk.files.OriginalVideoDecision
import io.putdotio.sdk.files.PlaybackCapabilities
import io.putdotio.sdk.files.PutioMediaInfo
import io.putdotio.sdk.files.PutioPlaybackIssue

public enum class OriginalVideoPolicy {
    Never,
    Check,
    Always,
}

public fun interface DecoderInventory {
    public fun plays(format: Format): Boolean
}

public class OriginalPlaybackDecision(
    private val decoders: DecoderInventory,
) : OriginalVideoDecision {
    public fun fallbackReason(mediaInfo: PutioMediaInfo?): PlaybackFallbackReason? {
        if (mediaInfo == null) return PlaybackFallbackReason.NoMediaInfo
        val issues = mediaInfo.playbackIssues.orEmpty()
        val streams = mediaInfo.streams.orEmpty()
        val video = MediaInfoFormats.videoStreams(streams).firstOrNull()
        val audio = MediaInfoFormats.audioStreams(streams)
        val videoFormat = video?.let(MediaInfoFormats::toFormat)
        val audioFormats = audio.mapNotNull(MediaInfoFormats::toFormat)
        return when {
            issues.any { it in FALLBACK_ISSUES } -> PlaybackFallbackReason.PlaybackIssue
            issues.any { it !in PLAYABLE_ISSUES } -> PlaybackFallbackReason.UnknownIssue
            streams.isEmpty() -> PlaybackFallbackReason.NoMediaInfo
            !MediaInfoFormats.playableContainer(mediaInfo.format) -> PlaybackFallbackReason.ContainerUnsupported
            videoFormat == null -> PlaybackFallbackReason.CodecUnmapped
            !decoders.plays(videoFormat) -> PlaybackFallbackReason.VideoUnsupported
            audio.isEmpty() -> null
            audioFormats.isEmpty() -> PlaybackFallbackReason.CodecUnmapped
            audioFormats.none(decoders::plays) -> PlaybackFallbackReason.AudioUnsupported
            else -> null
        }
    }

    override fun canPlayOriginal(
        mediaInfo: PutioMediaInfo?,
        needConvert: Boolean,
        isMp4Available: Boolean,
    ): Boolean = fallbackReason(mediaInfo) == null

    private companion object {
        val FALLBACK_ISSUES = setOf(
            PutioPlaybackIssue.MALFORMED_SEI,
            PutioPlaybackIssue.MALFORMED_PARAMETER_SETS,
            PutioPlaybackIssue.INTERLACED_VIDEO,
            PutioPlaybackIssue.EXCESSIVE_FRAME_RATE,
        )
        val PLAYABLE_ISSUES = setOf(
            PutioPlaybackIssue.OPEN_GOP,
            PutioPlaybackIssue.MOVIE_FRAGMENTS,
            PutioPlaybackIssue.SPARSE_IDR,
        )
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public class MediaCodecDecoderInventory(
    context: Context,
) : DecoderInventory {
    private val context = context.applicationContext

    override fun plays(format: Format): Boolean {
        val mimeType = format.sampleMimeType ?: return false
        return mimeType == MimeTypes.AUDIO_RAW ||
            decodes(format) ||
            MimeTypes.isAudio(mimeType) && passesThrough(format)
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

internal fun OriginalVideoPolicy.capabilities(decision: OriginalVideoDecision): PlaybackCapabilities =
    when (this) {
        OriginalVideoPolicy.Never -> PlaybackCapabilities()
        OriginalVideoPolicy.Check -> PlaybackCapabilities(originalVideoDecision = decision)
        OriginalVideoPolicy.Always -> PlaybackCapabilities(originalVideoPlayable = true)
    }
