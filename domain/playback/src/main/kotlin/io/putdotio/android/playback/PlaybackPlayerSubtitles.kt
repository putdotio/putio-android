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
    return if (added.isEmpty()) PlaybackSubtitles.None else PlaybackSubtitles.Sidecar(added)
}

internal fun PlaybackSource.inFileSubtitleLanguage(): String? {
    val ranked = subtitles.sidecarTracks().firstOrNull() ?: return null
    return ranked.languageCode
        ?.takeIf { it.isNotBlank() && kind == PlaybackSourceKind.ORIGINAL && ranked.source == SOURCE_MKV }
}

public fun Player.inFileSubtitleLanguage(): String? = currentMediaItem.inFileSubtitleLanguage()

internal fun MediaItem?.inFileSubtitleLanguage(): String? =
    this?.mediaMetadata?.extras?.getString(IN_FILE_SUBTITLE_LANGUAGE_KEY)

internal const val IN_FILE_SUBTITLE_LANGUAGE_KEY = "putio_in_file_subtitle_language"
