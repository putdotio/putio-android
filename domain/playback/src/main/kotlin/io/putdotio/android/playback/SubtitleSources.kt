package io.putdotio.android.playback

import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles

public class SubtitleSources(public val sidecar: List<PlaybackSubtitle>, public val originsKnown: Boolean)

public fun PlaybackSubtitles.sources(): SubtitleSources =
    SubtitleSources(sidecarTracks(), originsKnown = this !is PlaybackSubtitles.Embedded)

internal fun PlaybackSubtitle.fileStem(): String = name.trim().replace(SUBTITLE_EXTENSION, "")

private val SUBTITLE_EXTENSION =
    Regex("\\.(srt|vtt|webvtt|ass|ssa|sub|smi|sbv|ttml|dfxp|txt)$", RegexOption.IGNORE_CASE)
