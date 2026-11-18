package io.putdotio.android.playback

import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles

public class SubtitleSources(public val sidecar: List<PlaybackSubtitle>, public val originsKnown: Boolean)

public fun PlaybackSubtitles.sources(): SubtitleSources =
    SubtitleSources(sidecarTracks(), originsKnown = this !is PlaybackSubtitles.Embedded)

internal fun PlaybackSubtitle.fileStem(): String {
    val dot = name.lastIndexOf('.')
    val extended = dot > 0 && name.substring(0, dot).any { it != '.' }
    return (if (extended) name.substring(0, dot) else name).replace('"', '\'')
}
