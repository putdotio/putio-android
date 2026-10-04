package io.putdotio.android.downloads

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import io.putdotio.android.files.FilesItemId
import java.io.InputStream

/** One completed original-file download: its length and a reader from any offset, both from disk. */
internal class OfflineOriginal(
    val length: Long,
    val open: (offset: Long) -> InputStream,
)

/**
 * Finds a completed download that holds a file's original bytes. Audio downloads store the original; video
 * downloads store the HLS rendition the player streams, so they never qualify. The app's index must read the row
 * as completed, Media3's index must agree, and every byte must be in the cache under the owner's keys. Reads have
 * no upstream, so a span missing on disk fails instead of reaching the network.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class OfflineOriginals(
    private val cache: Cache,
    private val index: DownloadIndex,
    private val entry: (userId: Long, fileId: FilesItemId) -> DownloadEntry?,
) {
    fun find(userId: Long, fileId: FilesItemId): OfflineOriginal? {
        val uri = completedOriginal(userId, fileId)?.request?.uri ?: return null
        val keys = UserScopedCacheKeys(userId)
        val key = keys.buildCacheKey(DataSpec(uri))
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val reads = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(keys)
        return OfflineOriginal(length) { offset ->
            DataSourceInputStream(
                reads.createDataSource(),
                DataSpec.Builder().setUri(uri).setPosition(offset).build(),
            ).apply { open() }
        }.takeIf { length > 0L && cache.isCached(key, 0L, length) }
    }

    private fun completedOriginal(userId: Long, fileId: FilesItemId): Download? {
        val row = entry(userId, fileId)
        return index.getDownload(downloadContentId(userId, fileId))?.takeIf {
            row?.artifact == DownloadArtifact.ORIGINAL && row.isCompleted && it.state == Download.STATE_COMPLETED
        }
    }

    companion object {
        fun from(context: Context): OfflineOriginals {
            val downloads = MobileDownloadCache.get(context)
            return OfflineOriginals(downloads.cache, downloads.downloadManager.downloadIndex) { userId, fileId ->
                MobileDownloadStore(context.applicationContext, userId).find(fileId)
            }
        }
    }
}
