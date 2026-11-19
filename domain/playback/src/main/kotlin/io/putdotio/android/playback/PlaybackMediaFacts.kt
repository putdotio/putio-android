package io.putdotio.android.playback

import io.putdotio.sdk.files.PutioMediaInfo
import io.putdotio.sdk.files.PutioMediaStream
import java.util.Locale

/** The original's container, first video stream and first audio stream, as put.io probed them. */
internal fun mediaFacts(info: PutioMediaInfo?): Map<String, String> {
    val streams = info?.streams.orEmpty()
    if (info == null || streams.isEmpty()) return MEDIA_FACTS.associateWith { UNKNOWN_FACT }
    val video = MediaInfoFormats.videoStreams(streams).firstOrNull()
    val audio = MediaInfoFormats.audioStreams(streams).firstOrNull()
    // put.io SDK 2.4.0 reads neither bit depth nor HDR from media_info.
    return mapOf(
        "container_family" to containerFamily(info),
        "video_codec" to video.fact { it.codecName },
        "video_profile" to video.fact { it.profile },
        "video_level" to video.fact { stream -> stream.level?.takeIf { it >= 0 }?.toString() },
        "video_bit_depth" to video.fact { null },
        "video_resolution" to video.fact(::resolution),
        "video_hdr" to video.fact { null },
        "audio_codec" to audio.fact { it.codecName },
        "audio_channels" to audio.fact { stream -> stream.channels?.takeIf { it > 0 }?.toString() },
    )
}

private fun PutioMediaStream?.fact(read: (PutioMediaStream) -> String?): String =
    if (this == null) {
        NO_STREAM
    } else {
        read(this)?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: UNKNOWN_FACT
    }

private fun resolution(stream: PutioMediaStream): String? {
    val width = stream.width?.takeIf { it > 0 }
    val height = stream.height?.takeIf { it > 0 }
    return if (width != null && height != null) "${width}x$height" else null
}

private fun containerFamily(info: PutioMediaInfo): String {
    val names = info.format?.name?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }.orEmpty()
    return when {
        names.all(String::isEmpty) -> UNKNOWN_FACT
        info.mimeType?.lowercase(Locale.ROOT) == "video/webm" -> "webm"
        "matroska" in names -> "matroska"
        "mp4" in names || "mov" in names -> "mp4"
        "avi" in names -> "avi"
        "mpegts" in names -> "mpeg-ts"
        "webm" in names -> "webm"
        "ogg" in names -> "ogg"
        else -> "other"
    }
}

private const val NO_STREAM = "none"
private const val UNKNOWN_FACT = "unknown"

private val MEDIA_FACTS = listOf(
    "container_family",
    "video_codec",
    "video_profile",
    "video_level",
    "video_bit_depth",
    "video_resolution",
    "video_hdr",
    "audio_codec",
    "audio_channels",
)
