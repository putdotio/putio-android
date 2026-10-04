package io.putdotio.android.tv.watchnext

import io.putdotio.android.files.FilesItem
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PutioFileType

/** A file this TV played or marked, with what the Watch Next row needs to show it. */
internal data class TvWatchNextMedia(
    val fileId: Long,
    val title: String,
    val durationSeconds: Double?,
    val posterUrl: String?,
    val isVideo: Boolean,
)

/** One video's card in the launcher's Watch Next row ("Play Next", "Continue watching"). */
internal data class TvWatchNextProgram(
    val fileId: Long,
    val title: String,
    val posterUrl: String?,
    val positionMillis: Long,
    val durationMillis: Long,
)

internal sealed interface TvWatchNextChange {
    data class Publish(val program: TvWatchNextProgram) : TvWatchNextChange

    data class Remove(val fileId: Long) : TvWatchNextChange

    /** Nothing to say: not a video, or no duration to show progress against. */
    data object None : TvWatchNextChange
}

/**
 * What a saved position means for the row. A video is in progress above 0 % and below 95 %
 * (#38's completed line); a position within 10 s of the end also counts as finished, since
 * opening it then starts over (see `PlaybackReducer`). Unwatched and finished videos leave.
 */
internal fun watchNextChange(media: TvWatchNextMedia, positionSeconds: Double): TvWatchNextChange {
    val duration = media.durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
    return when {
        !media.isVideo -> TvWatchNextChange.None
        !positionSeconds.isFinite() || positionSeconds <= 0.0 -> TvWatchNextChange.Remove(media.fileId)
        duration == null -> TvWatchNextChange.None
        positionSeconds >= duration * COMPLETED_FRACTION ||
            positionSeconds >= duration - FINISHED_WITHIN_SECONDS -> TvWatchNextChange.Remove(media.fileId)
        else -> TvWatchNextChange.Publish(
            TvWatchNextProgram(
                fileId = media.fileId,
                title = media.title,
                posterUrl = media.posterUrl,
                positionMillis = (positionSeconds * MILLIS_PER_SECOND).toLong(),
                durationMillis = (duration * MILLIS_PER_SECOND).toLong(),
            ),
        )
    }
}

internal fun PlaybackTarget.toWatchNextMedia(): TvWatchNextMedia =
    TvWatchNextMedia(
        fileId = fileId.value,
        title = name,
        durationSeconds = durationSeconds,
        posterUrl = posterUrl,
        isVideo = mediaType == PlaybackMediaType.VIDEO,
    )

internal fun FilesItem.toWatchNextMedia(): TvWatchNextMedia =
    TvWatchNextMedia(
        fileId = id.value,
        title = name,
        durationSeconds = playback?.durationSeconds,
        posterUrl = screenshotUrl,
        isVideo = type == PutioFileType.VIDEO,
    )

private const val COMPLETED_FRACTION = 0.95
private const val FINISHED_WITHIN_SECONDS = 10.0
private const val MILLIS_PER_SECOND = 1_000.0
