package io.putdotio.android.downloads

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A real Media3 cache and download index; only the bytes are written directly instead of downloaded. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OfflineOriginalsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = StandaloneDatabaseProvider(context)
    private val directory = File(context.cacheDir, "offline-originals-${UUID.randomUUID()}")
    private val cache = SimpleCache(directory, NoOpCacheEvictor(), database)
    private val index = DefaultDownloadIndex(database, "OfflineOriginalsTest")
    private val rows = mutableMapOf<Pair<Long, FilesItemId>, DownloadEntry>()
    private val originals = OfflineOriginals(cache, index) { userId, fileId -> rows[userId to fileId] }
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }

    @After
    fun tearDown() {
        cache.release()
        directory.deleteRecursively()
    }

    @Test
    fun aCompletedAudioDownloadReadsItsOriginalFromDiskAtAnyOffset() {
        download(fileId = 9L, DownloadArtifact.ORIGINAL)

        val original = checkNotNull(originals.find(ALICE, FilesItemId(9L)))

        assertEquals(bytes.size.toLong(), original.length)
        assertArrayEquals(bytes, original.open(0L).readBytes())
        assertArrayEquals(bytes.copyOfRange(123_456, bytes.size), original.open(123_456L).readBytes())
    }

    @Test
    fun onlyTheOwnersCompleteOriginalQualifies() {
        download(fileId = 9L, DownloadArtifact.ORIGINAL)
        // A video download holds the HLS rendition, not the file a picker asked for.
        download(fileId = 10L, DownloadArtifact.HLS)
        download(fileId = 11L, DownloadArtifact.ORIGINAL, row = DownloadStatus.Downloading(0L, null))
        download(fileId = 12L, DownloadArtifact.ORIGINAL, state = Download.STATE_REMOVING)
        download(fileId = 13L, DownloadArtifact.ORIGINAL, cachedBytes = bytes.size / 2)

        assertNull(originals.find(BOB, FilesItemId(9L)))
        for (fileId in 10L..13L) assertNull("file $fileId", originals.find(ALICE, FilesItemId(fileId)))
    }

    private fun download(
        fileId: Long,
        artifact: DownloadArtifact,
        row: DownloadStatus = DownloadStatus.Completed(bytes.size.toLong()),
        state: Int = Download.STATE_COMPLETED,
        cachedBytes: Int = bytes.size,
    ) {
        val id = FilesItemId(fileId)
        val uri = Uri.parse(artifact.apiUrl(id))
        val keys = UserScopedCacheKeys(ALICE)
        val writer = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(keys)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(bytes.copyOf(cachedBytes)) }
            .createDataSource()
        CacheWriter(writer, DataSpec(uri), null, null).cache()
        // A transfer cut short knows the whole length but holds only part of it.
        val key = keys.buildCacheKey(DataSpec(uri))
        cache.applyContentMetadataMutations(
            key,
            ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, bytes.size.toLong()) },
        )
        index.putDownload(
            Download(
                DownloadRequest.Builder(downloadContentId(ALICE, id), uri).build(),
                state,
                0L,
                0L,
                bytes.size.toLong(),
                0,
                Download.FAILURE_REASON_NONE,
                DownloadProgress(),
            ),
        )
        rows[ALICE to id] = DownloadEntry(
            fileId = id,
            name = "file-$fileId",
            type = if (artifact == DownloadArtifact.HLS) PutioFileType.VIDEO else PutioFileType.AUDIO,
            artifact = artifact,
            status = row,
            createdAt = fileId,
            accepted = true,
        )
    }

    private companion object {
        const val ALICE = 101L
        const val BOB = 202L
    }
}
