package io.putdotio.android.playback

import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles

public fun PlaybackSource.playerSubtitles(): PlaybackSubtitles {
    val sidecar = subtitles as? PlaybackSubtitles.Sidecar
    if (kind != PlaybackSourceKind.ORIGINAL || sidecar == null) return subtitles
    val added = sidecar.tracks.filterNot { it.source == SOURCE_MKV }
    return if (added.isEmpty()) PlaybackSubtitles.None else PlaybackSubtitles.Sidecar(added)
}
