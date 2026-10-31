package io.putdotio.android.playback

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import java.util.Locale

/** The preset speeds; every speed control snaps to one. */
@Suppress("MagicNumber") // The presets are the contract's own list (DESIGN.md Player).
public val PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)

/** The speed sheet's quick choices. */
@Suppress("MagicNumber") // DESIGN.md Player names these four.
public val QUICK_PLAYBACK_SPEEDS: List<Float> = listOf(1f, 1.25f, 1.5f, 2f)

/** A speed as the app writes it: "1", "1.25", "0.5". */
public fun formatPlaybackSpeed(speed: Float): String = speed.toString().removeSuffix(".0")

/** The preset one step slower or faster than [speed], or null at either end. */
public fun adjacentPlaybackSpeed(speed: Float, faster: Boolean): Float? =
    if (faster) {
        PLAYBACK_SPEEDS.firstOrNull { it > speed }
    } else {
        PLAYBACK_SPEEDS.lastOrNull { it < speed }
    }

public fun playbackSpeedStop(speed: Float): Int =
    PLAYBACK_SPEEDS.indexOf(speed).takeIf { it >= 0 }
        ?: PLAYBACK_SPEEDS.indexOfLast { it <= speed }.coerceAtLeast(0)

public fun audioCodecName(mimeType: String?): String? = AUDIO_CODEC_NAMES[mimeType]

private val AUDIO_CODEC_NAMES = mapOf(
    MimeTypes.AUDIO_AC3 to "AC3",
    MimeTypes.AUDIO_E_AC3 to "E-AC3",
    MimeTypes.AUDIO_E_AC3_JOC to "E-AC3",
    MimeTypes.AUDIO_AC4 to "AC4",
    MimeTypes.AUDIO_AAC to "AAC",
    MimeTypes.AUDIO_MPEG to "MP3",
    MimeTypes.AUDIO_MPEG_L2 to "MP2",
    MimeTypes.AUDIO_OPUS to "Opus",
    MimeTypes.AUDIO_VORBIS to "Vorbis",
    MimeTypes.AUDIO_FLAC to "FLAC",
    MimeTypes.AUDIO_ALAC to "ALAC",
    MimeTypes.AUDIO_DTS to "DTS",
    MimeTypes.AUDIO_DTS_EXPRESS to "DTS",
    MimeTypes.AUDIO_DTS_HD to "DTS-HD",
    MimeTypes.AUDIO_TRUEHD to "TrueHD",
    MimeTypes.AUDIO_RAW to "PCM",
)

/** A language tag in its own language, capitalised as that language does; null when unknown. */
public fun languageName(tag: String?): String? {
    val locale = tag?.takeUnless { it.isBlank() || it == C.LANGUAGE_UNDETERMINED }?.let(Locale::forLanguageTag)
        ?: return null
    return locale.getDisplayLanguage(locale)
        .takeUnless { it.isBlank() || it.equals(locale.language, ignoreCase = true) }
        ?.replaceFirstChar { it.titlecase(locale) }
}
