package io.putdotio.android.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles

public fun PlaybackSource.playerSubtitles(): PlaybackSubtitles {
    val sidecar = subtitles as? PlaybackSubtitles.Sidecar
    if (kind != PlaybackSourceKind.ORIGINAL || sidecar == null) return subtitles
    val added = sidecar.tracks.filterNot { it.source == SOURCE_MKV }
    return if (added.isEmpty()) {
        PlaybackSubtitles.None
    } else {
        PlaybackSubtitles.Sidecar(
            tracks = added,
            defaultKey = sidecar.defaultKey?.takeIf { key -> key.isNotBlank() && added.any { it.key == key } },
        )
    }
}

internal fun PlaybackSource.inFileSubtitleLanguage(): String? {
    val sidecar = subtitles as? PlaybackSubtitles.Sidecar
    val defaultTrack = sidecar?.defaultKey?.takeIf(String::isNotBlank)
        ?.let { key -> sidecar.tracks.firstOrNull { it.key == key } }
    return defaultTrack?.languageCode
        ?.takeIf { it.isNotBlank() && kind == PlaybackSourceKind.ORIGINAL && defaultTrack.source == SOURCE_MKV }
}

public fun Player.inFileSubtitleLanguage(): String? = currentMediaItem.inFileSubtitleLanguage()

internal fun MediaItem?.inFileSubtitleLanguage(): String? =
    this?.mediaMetadata?.extras?.getString(IN_FILE_SUBTITLE_LANGUAGE_KEY)

internal const val IN_FILE_SUBTITLE_LANGUAGE_KEY = "putio_in_file_subtitle_language"
