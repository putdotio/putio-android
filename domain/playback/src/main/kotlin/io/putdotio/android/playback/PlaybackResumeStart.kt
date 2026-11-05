package io.putdotio.android.playback

public fun PlaybackResolution.Ready.unattendedStartMillis(durationSeconds: Double?): Long =
    source.startFromSeconds
        .takeIf { useStartFrom && it > 0.0 && !finishedAt(it, durationSeconds ?: this.durationSeconds) }
        ?.toPlaybackMillis() ?: 0L

internal fun finishedAt(savedSeconds: Double, durationSeconds: Double?): Boolean =
    durationSeconds != null && savedSeconds >= durationSeconds - FINISHED_WITHIN_SECONDS

private const val FINISHED_WITHIN_SECONDS = 10.0
