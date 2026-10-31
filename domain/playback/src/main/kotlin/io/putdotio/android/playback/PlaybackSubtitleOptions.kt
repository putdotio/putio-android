package io.putdotio.android.playback

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import java.util.Locale
import kotlin.math.abs

internal fun subtitleFormatName(mimeType: String?): String? =
    when (mimeType) {
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.TEXT_SSA -> "SSA"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        MimeTypes.APPLICATION_TX3G -> "TX3G"
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608 -> "CEA-608"
        MimeTypes.APPLICATION_CEA708 -> "CEA-708"
        else -> null
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun PlaybackSubtitleTrack.formatName(): String? =
    group.getFormat(trackIndex).let { format ->
        subtitleFormatName(format.codecs)
            ?: subtitleFormatName(format.codecs?.let(MimeTypes::getTextMediaMimeType) ?: format.sampleMimeType)
    }

public enum class SubtitleOrigin { File, Folder, OpenSubtitles }

public fun PlaybackSubtitleTrack.putioSubtitle(sidecar: List<PlaybackSubtitle>): PlaybackSubtitle? {
    val id = identity.id ?: return null
    return sidecar.firstOrNull { id == it.key || id.endsWith(":${it.key}") }
}

public fun PlaybackSubtitle?.origin(): SubtitleOrigin =
    when (this?.source) {
        null, SOURCE_MKV -> SubtitleOrigin.File
        SOURCE_FOLDER -> SubtitleOrigin.Folder
        else -> SubtitleOrigin.OpenSubtitles
    }

private const val SOURCE_MKV = "mkv"
private const val SOURCE_FOLDER = "folder"

public fun PlaybackSubtitles?.sidecarTracks(): List<PlaybackSubtitle> =
    (this as? PlaybackSubtitles.Sidecar)?.tracks.orEmpty()

public enum class SubtitleTextSize(
    public val scale: Float,
    public val short: String,
    @get:StringRes public val label: Int,
) {
    Small(SMALL_SCALE, "S", R.string.playback_size_small),
    Medium(1f, "M", R.string.playback_size_medium),
    Large(LARGE_SCALE, "L", R.string.playback_size_large),
    ExtraLarge(EXTRA_LARGE_SCALE, "XL", R.string.playback_size_extra_large),
}

private const val SMALL_SCALE = 0.75f
private const val LARGE_SCALE = 1.25f
private const val EXTRA_LARGE_SCALE = 1.5f

public const val SUBTITLE_OFFSET_STEP_MILLIS: Long = 100L
public const val SUBTITLE_OFFSET_LIMIT_MILLIS: Long = 10_000L

public fun formatSubtitleOffset(offsetMillis: Long): String {
    val seconds = String.format(Locale.ROOT, "%.1f", abs(offsetMillis) / MILLIS_PER_SECOND)
    return when {
        offsetMillis > 0 -> "+$seconds s"
        offsetMillis < 0 -> "−$seconds s"
        else -> "$seconds s"
    }
}

private const val MILLIS_PER_SECOND = 1_000.0

@Stable
public class SubtitleAppearanceStore(private val context: Context?) {
    public var textSize: SubtitleTextSize by mutableStateOf(read())
        private set

    public fun update(size: SubtitleTextSize) {
        textSize = size
        context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)?.edit {
            putString(TEXT_SIZE_KEY, size.name)
        }
    }

    private fun read(): SubtitleTextSize {
        val stored = context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.getString(TEXT_SIZE_KEY, null)
        return SubtitleTextSize.entries.firstOrNull { it.name == stored } ?: SubtitleTextSize.Medium
    }

    private companion object {
        const val PREFERENCES_NAME = "io.put.player"
        const val TEXT_SIZE_KEY = "subtitle_text_size"
    }
}

@Composable
public fun rememberSubtitleAppearanceStore(): SubtitleAppearanceStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { SubtitleAppearanceStore(context) }
}
