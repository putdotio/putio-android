package io.putdotio.android.playback

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
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
        val videoFormat = streams.firstVideoStream()?.let(MediaInfoFormats::toFormat)
        val audio = streams.defaultAudioStream()
        val audioFormat = audio?.let(MediaInfoFormats::toFormat)
        return when {
            videoFormat == null -> PlaybackFallbackReason.CodecUnmapped
            !decoders.plays(videoFormat) -> PlaybackFallbackReason.VideoUnsupported
            audio == null -> null
            audioFormat == null -> PlaybackFallbackReason.CodecUnmapped
            !decoders.plays(audioFormat) -> PlaybackFallbackReason.AudioUnsupported
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

    private fun decodes(format: Format): Boolean {
        return try {
            val mimeType = format.sampleMimeType
            val decoders = if (mimeType == MimeTypes.VIDEO_DOLBY_VISION &&
                (CodecSpecificDataUtil.getCodecProfileAndLevel(format) ?: return false).first ==
                CodecProfileLevel.DolbyVisionProfileDvav110
            ) {
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, false, false)
            } else {
                MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false)
            }
            decoders.any { it.isFormatSupported(context, format) && it.supportsVp9Hdr(format) }
        } catch (_: MediaCodecUtil.DecoderQueryException) {
            false
        }
    }

    private fun MediaCodecInfo.supportsVp9Hdr(format: Format): Boolean {
        if (format.sampleMimeType != MimeTypes.VIDEO_VP9 || !ColorInfo.isTransferHdr(format.colorInfo)) return true
        val profileAndLevel = CodecSpecificDataUtil.getCodecProfileAndLevel(format)
        val hdrProfiles = vp9HdrProfiles(profileAndLevel?.first)
        return profileAndLevel != null &&
            profileLevels.any { it.profile in hdrProfiles && it.level >= profileAndLevel.second }
    }

    private fun vp9HdrProfiles(profile: Int?): Set<Int> = buildSet {
        when (profile) {
            CodecProfileLevel.VP9Profile2 -> {
                add(CodecProfileLevel.VP9Profile2HDR)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(CodecProfileLevel.VP9Profile2HDR10Plus)
            }
            CodecProfileLevel.VP9Profile3 -> {
                add(CodecProfileLevel.VP9Profile3HDR)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(CodecProfileLevel.VP9Profile3HDR10Plus)
            }
        }
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
