package io.putdotio.android.files

public fun playbackFinishedAt(positionSeconds: Double, durationSeconds: Double?): Boolean =
    durationSeconds != null && durationSeconds.isFinite() && durationSeconds > 0.0 &&
        positionSeconds >= durationSeconds * FINISHED_FRACTION

private const val FINISHED_FRACTION = 0.95
