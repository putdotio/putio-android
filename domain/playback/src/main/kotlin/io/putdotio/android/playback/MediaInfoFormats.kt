package io.putdotio.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.putdotio.sdk.files.PutioMediaFormat
import io.putdotio.sdk.files.PutioMediaStream
import java.util.Locale

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object MediaInfoFormats {
    fun playableContainer(format: PutioMediaFormat?): Boolean =
        format?.name
            ?.split(',')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.any { it in PLAYABLE_CONTAINERS } == true

    fun hlsOriginalContainer(mimeType: String?): Boolean = mimeType in HLS_ORIGINAL_MIME_TYPES

    fun videoStreams(streams: List<PutioMediaStream>): List<PutioMediaStream> =
        streams.filter { it.codecType == VIDEO && it.codecName?.lowercase(Locale.ROOT) !in STILL_IMAGE_CODECS }

    fun audioStreams(streams: List<PutioMediaStream>): List<PutioMediaStream> =
        streams.filter { it.codecType == AUDIO }

    fun toFormat(stream: PutioMediaStream): Format? {
        val codec = stream.codecName?.lowercase(Locale.ROOT) ?: return null
        return when (stream.codecType) {
            VIDEO -> videoFormat(codec, stream)
            AUDIO -> audioFormat(codec, stream)
            else -> null
        }
    }

    private fun videoFormat(codec: String, stream: PutioMediaStream): Format? {
        val mimeType = VIDEO_MIME_TYPES[codec] ?: return null
        return Format.Builder()
            .setSampleMimeType(mimeType)
            .setCodecs(stream.rfc6381Codec?.takeIf(String::isNotBlank) ?: videoCodecs(codec, stream))
            .setWidth(stream.width?.takeIf { it > 0 } ?: Format.NO_VALUE)
            .setHeight(stream.height?.takeIf { it > 0 } ?: Format.NO_VALUE)
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

    private fun videoCodecs(codec: String, stream: PutioMediaStream): String? {
        val level = stream.level?.takeIf { it > 0 }
        val profile = stream.profile?.trim()?.lowercase(Locale.ROOT)
        return when {
            codec == VP9 -> VP9_PROFILES[profile]?.let { (number, bitDepth) ->
                "vp09.$number.%02d.$bitDepth".format(Locale.ROOT, level ?: VP9_LOWEST_LEVEL)
            }
            level == null || profile == null -> null
            codec == H264 -> AVC_PROFILES[profile]?.let { "avc1.${it}00%02x".format(Locale.ROOT, level) }
            codec == HEVC -> HEVC_PROFILES[profile]?.let { "hvc1.$it.L$level.B0" }
            codec == AV1 -> AV1_PROFILES[profile]?.let { "av01.$it.%02dM.10".format(Locale.ROOT, level) }
            else -> null
        }
    }

    private fun dtsMimeType(profile: String?): String {
        val name = profile?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "express" in name -> MimeTypes.AUDIO_DTS_EXPRESS
            "dts-hd" in name -> MimeTypes.AUDIO_DTS_HD
            else -> MimeTypes.AUDIO_DTS
        }
    }

    private const val VIDEO = "video"
    private const val AUDIO = "audio"
    private const val H264 = "h264"
    private const val HEVC = "hevc"
    private const val AV1 = "av1"
    private const val VP9 = "vp9"
    private const val VP9_LOWEST_LEVEL = 10
    private const val DTS = "dts"

    private val PLAYABLE_CONTAINERS = setOf(
        "matroska", "webm", "mp4", "mov", "m4a", "m4v", "3gp", "3g2", "avi", "mpegts", "flv", "ogg", "mpeg",
    )

    private val HLS_ORIGINAL_MIME_TYPES = setOf("video/mp4", "audio/mp4", "video/x-m4v")

    private val STILL_IMAGE_CODECS = setOf("mjpeg", "png", "bmp", "gif", "webp")

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
        "profile 0" to ("00" to "08"),
        "profile 1" to ("01" to "08"),
        "profile 2" to ("02" to "10"),
        "profile 3" to ("03" to "10"),
    )

    private val AV1_PROFILES = mapOf(
        "main" to "0",
        "high" to "1",
        "professional" to "2",
    )
}
