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

internal class OfflineOriginal(
    val length: Long,
    val open: (offset: Long) -> InputStream,
)

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
