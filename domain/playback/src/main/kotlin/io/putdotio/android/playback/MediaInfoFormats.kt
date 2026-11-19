package io.putdotio.android.playback

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.putdotio.sdk.files.PutioMediaFormat
import io.putdotio.sdk.files.PutioMediaStream
import java.util.Locale

private const val VIDEO = "video"
private const val AUDIO = "audio"

internal fun List<PutioMediaStream>.firstVideoStream(): PutioMediaStream? =
    firstOrNull { it.codecType == VIDEO && it.codecName?.lowercase(Locale.ROOT) !in STILL_IMAGE_CODECS }

internal fun List<PutioMediaStream>.defaultAudioStream(): PutioMediaStream? =
    firstOrNull { it.codecType == AUDIO && it.isDefault == true }
        ?: firstOrNull { it.codecType == AUDIO }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object MediaInfoFormats {
    fun playableContainer(format: PutioMediaFormat?): Boolean =
        format?.name
            ?.split(',')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.any { it in PLAYABLE_CONTAINERS } == true

    fun toFormat(stream: PutioMediaStream): Format? {
        val codec = stream.codecName?.lowercase(Locale.ROOT) ?: return null
        return when (stream.codecType) {
            VIDEO -> videoFormat(codec, stream)
            AUDIO -> audioFormat(codec, stream)
            else -> null
        }
    }

    private fun videoFormat(codec: String, stream: PutioMediaStream): Format? {
        val rfcCodecs = stream.rfc6381Codec?.takeIf(String::isNotBlank)
        val dolbyCodecs = rfcCodecs?.takeIf { it.substringBefore('.').lowercase(Locale.ROOT) in DOLBY_VISION_CODEC_IDS }
        val dolbyVision = stream.dolbyVisionProfile != null || dolbyCodecs != null
        val mimeType = if (dolbyVision) MimeTypes.VIDEO_DOLBY_VISION else VIDEO_MIME_TYPES[codec] ?: return null
        val codecs = if (dolbyVision) {
            dolbyCodecs
        } else {
            rfcCodecs?.withBitDepth(codec, stream.bitDepth) ?: videoCodecs(codec, stream)
        }
        val depth = stream.bitDepth ?: bitDepth(codec, codecs)
        return Format.Builder()
            .setSampleMimeType(mimeType)
            .setCodecs(codecs)
            .setColorInfo(colorInfo(depth, stream.isHdr, dolbyVision))
            .setWidth(stream.width?.takeIf { it > 0 } ?: Format.NO_VALUE)
            .setHeight(stream.height?.takeIf { it > 0 } ?: Format.NO_VALUE)
            .build()
    }

    private fun colorInfo(depth: Int?, hdr: Boolean?, dolbyVision: Boolean): ColorInfo {
        val needsHdrDecoder = dolbyVision || (hdr ?: (depth != null && depth > SDR_BIT_DEPTH))
        val bitDepth = depth ?: Format.NO_VALUE
        return ColorInfo.Builder()
            .setColorTransfer(if (needsHdrDecoder) C.COLOR_TRANSFER_ST2084 else C.COLOR_TRANSFER_SDR)
            .setLumaBitdepth(bitDepth)
            .setChromaBitdepth(bitDepth)
            .build()
    }

    private fun audioFormat(codec: String, stream: PutioMediaStream): Format? {
        val pcmEncoding = PCM_ENCODINGS[codec]
        val mimeType = when {
            pcmEncoding != null -> MimeTypes.AUDIO_RAW
            codec == DTS -> dtsMimeType(stream.profile)
            else -> AUDIO_MIME_TYPES[codec]
        } ?: return null
        return Format.Builder()
            .setSampleMimeType(mimeType)
            .setCodecs(stream.rfc6381Codec?.takeIf(String::isNotBlank))
            .setChannelCount(stream.channels?.takeIf { it > 0 } ?: Format.NO_VALUE)
            .setPcmEncoding(pcmEncoding ?: Format.NO_VALUE)
            .build()
    }

    private fun bitDepth(codec: String, codecs: String?): Int? {
        val parts = codecs?.split('.') ?: return null
        return when (codec) {
            AV1, VP9 -> parts.getOrNull(CODEC_BIT_DEPTH_INDEX)?.toIntOrNull()
            HEVC -> if (parts.getOrNull(1) == "2") MAIN_10_BIT_DEPTH else null
            else -> null
        }
    }

    private fun String.withBitDepth(codec: String, depth: Int?): String {
        if (depth == null || codec !in BIT_DEPTH_CODECS) return this
        val parts = split('.').toMutableList()
        if (CODEC_BIT_DEPTH_INDEX in parts.indices) {
            parts[CODEC_BIT_DEPTH_INDEX] = "%02d".format(Locale.ROOT, depth)
        }
        return parts.joinToString(".")
    }

    private fun videoCodecs(codec: String, stream: PutioMediaStream): String? {
        val level = stream.level?.takeIf { it > 0 }
        val profile = stream.profile?.trim()?.lowercase(Locale.ROOT)
        return when {
            codec == VP9 -> vp9Codecs(profile, level, stream.bitDepth)
            level == null || profile == null -> null
            codec == H264 -> AVC_PROFILES[profile]?.let { "avc1.${it}00%02x".format(Locale.ROOT, level) }
            codec == HEVC -> HEVC_PROFILES[profile]?.let { "hvc1.$it.L$level.B0" }
            codec == AV1 -> AV1_PROFILES[profile]?.let {
                "av01.$it.%02dM.%02d".format(Locale.ROOT, level, stream.bitDepth ?: MAIN_10_BIT_DEPTH)
            }
            else -> null
        }
    }

    private fun vp9Codecs(profile: String?, level: Int?, depth: Int?): String? =
        VP9_PROFILES[profile]?.let { (number, maxDepth) ->
            "vp09.$number.%02d.%02d".format(Locale.ROOT, level ?: VP9_LOWEST_LEVEL, depth ?: maxDepth)
        }

    private fun dtsMimeType(profile: String?): String {
        val name = profile?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "express" in name -> MimeTypes.AUDIO_DTS_EXPRESS
            "dts-hd" in name -> MimeTypes.AUDIO_DTS_HD
            else -> MimeTypes.AUDIO_DTS
        }
    }

    private const val H264 = "h264"
    private const val HEVC = "hevc"
    private const val AV1 = "av1"
    private const val VP9 = "vp9"
    private const val VP9_LOWEST_LEVEL = 10
    private const val DTS = "dts"
    private const val SDR_BIT_DEPTH = 8
    private const val MAIN_10_BIT_DEPTH = 10
    private const val VP9_MAX_BIT_DEPTH = 12
    private const val CODEC_BIT_DEPTH_INDEX = 3

    private val BIT_DEPTH_CODECS = setOf(AV1, VP9)
    private val DOLBY_VISION_CODEC_IDS = setOf("dvh1", "dvhe", "dva1", "dvav", "dav1")

    private val PLAYABLE_CONTAINERS = setOf(
        "matroska", "webm", "mp4", "mov", "m4a", "m4v", "3gp", "3g2", "avi", "mpegts", "flv", "ogg", "mpeg",
    )

    private val VIDEO_MIME_TYPES = mapOf(
        H264 to MimeTypes.VIDEO_H264,
        HEVC to MimeTypes.VIDEO_H265,
        AV1 to MimeTypes.VIDEO_AV1,
        "vp8" to MimeTypes.VIDEO_VP8,
        VP9 to MimeTypes.VIDEO_VP9,
        "mpeg4" to MimeTypes.VIDEO_MP4V,
        "mpeg2video" to MimeTypes.VIDEO_MPEG2,
        "h263" to MimeTypes.VIDEO_H263,
    )

    private val AUDIO_MIME_TYPES = mapOf(
        "aac" to MimeTypes.AUDIO_AAC,
        "mp3" to MimeTypes.AUDIO_MPEG,
        "mp2" to MimeTypes.AUDIO_MPEG_L2,
        "ac3" to MimeTypes.AUDIO_AC3,
        "eac3" to MimeTypes.AUDIO_E_AC3,
        "truehd" to MimeTypes.AUDIO_TRUEHD,
        "opus" to MimeTypes.AUDIO_OPUS,
        "vorbis" to MimeTypes.AUDIO_VORBIS,
        "flac" to MimeTypes.AUDIO_FLAC,
        "alac" to MimeTypes.AUDIO_ALAC,
    )

    private val PCM_ENCODINGS = mapOf(
        "pcm_u8" to C.ENCODING_PCM_8BIT,
        "pcm_s16le" to C.ENCODING_PCM_16BIT,
        "pcm_s16be" to C.ENCODING_PCM_16BIT_BIG_ENDIAN,
        "pcm_s24le" to C.ENCODING_PCM_24BIT,
        "pcm_s32le" to C.ENCODING_PCM_32BIT,
        "pcm_f32le" to C.ENCODING_PCM_FLOAT,
    )

    private val AVC_PROFILES = mapOf(
        "baseline" to "42",
        "constrained baseline" to "42",
        "main" to "4d",
        "extended" to "58",
        "high" to "64",
        "high 10" to "6e",
        "high 4:2:2" to "7a",
        "high 4:4:4 predictive" to "f4",
    )

    private val HEVC_PROFILES = mapOf(
        "main" to "1.6",
        "main 10" to "2.4",
        "main still picture" to "3.8",
        "rext" to "4.10",
    )

    private val VP9_PROFILES = mapOf(
        "profile 0" to ("00" to SDR_BIT_DEPTH),
        "profile 1" to ("01" to SDR_BIT_DEPTH),
        "profile 2" to ("02" to VP9_MAX_BIT_DEPTH),
        "profile 3" to ("03" to VP9_MAX_BIT_DEPTH),
    )

    private val AV1_PROFILES = mapOf(
        "main" to "0",
        "high" to "1",
        "professional" to "2",
    )
}

private val STILL_IMAGE_CODECS = setOf("mjpeg", "png", "bmp", "gif", "webp")
