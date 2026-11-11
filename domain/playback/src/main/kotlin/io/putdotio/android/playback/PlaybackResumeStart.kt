package io.putdotio.android.playback

import io.putdotio.android.files.playbackFinishedAt

public fun PlaybackResolution.Ready.unattendedStartMillis(durationSeconds: Double?): Long =
    source.startFromSeconds
        .takeIf { useStartFrom && it > 0.0 && !playbackFinishedAt(it, durationSeconds ?: this.durationSeconds) }
        ?.toPlaybackMillis() ?: 0L
