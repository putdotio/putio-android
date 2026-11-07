package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItem
import io.putdotio.android.settings.StreamingMethod

internal class MobileFileDownloads(
    private val controller: DownloadsController,
    private val showSubtitles: Boolean?,
    private val onStarted: () -> Unit,
) {
    val withMethod: (FilesItem, StreamingMethod?) -> Unit = { item, method ->
        if (controller.dispatch(DownloadsEvent.Start(item.toDownloadRequest(showSubtitles, method)))) onStarted()
    }

    val bySetting: (FilesItem) -> Unit = { item -> withMethod(item, null) }
}

/** The confirmed subtitle setting travels with the download, which skips subtitles for it. */
private fun FilesItem.toDownloadRequest(showSubtitles: Boolean?, method: StreamingMethod?): DownloadRequest =
    DownloadRequest(
        fileId = id,
        name = name,
        type = type,
        sizeBytes = sizeBytes,
        subtitlesHidden = showSubtitles?.not(),
        startFromSeconds = playback?.startFromSeconds ?: 0.0,
        durationSeconds = playback?.durationSeconds,
        method = method,
    )
