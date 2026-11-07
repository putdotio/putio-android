package io.putdotio.android.downloads

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Downloader

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class SubtitledDownloader(
    private val download: Downloader,
    private val subtitles: List<Downloader>,
) : Downloader {
    @Volatile
    private var cancelled = false

    @Volatile
    private var current: Downloader? = null

    override fun download(progressListener: Downloader.ProgressListener?) {
        for (subtitle in subtitles) {
            run(subtitle) {
                try {
                    it.download(null)
                } catch (_: HttpDataSource.InvalidResponseCodeException) {
                }
            }
        }
        run(download) { it.download(progressListener) }
    }

    private inline fun run(downloader: Downloader, block: (Downloader) -> Unit) {
        current = downloader
        if (cancelled) throw InterruptedException()
        block(downloader)
    }

    override fun cancel() {
        cancelled = true
        current?.cancel()
    }

    override fun remove() {
        download.remove()
        subtitles.forEach(Downloader::remove)
    }
}
