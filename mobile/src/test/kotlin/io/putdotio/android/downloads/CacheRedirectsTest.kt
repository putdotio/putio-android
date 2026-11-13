package io.putdotio.android.downloads

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CacheRedirectsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = StandaloneDatabaseProvider(context)
    private val directory = File(context.cacheDir, "cache-redirects-${UUID.randomUUID()}")
    private var cache = SimpleCache(directory, NoOpCacheEvictor(), database)
    private val keys = UserScopedCacheKeys(ALICE)
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }

    @After
    fun tearDown() {
        cache.release()
        directory.deleteRecursively()
    }

    @Test
    fun aDownloadThroughACdnRedirectStoresNoSignedUrlAndResumesThroughTheRequestUrl() {
        val cdn = RedirectingUpstream(bytes)
        val reads = cacheFactory { RequestUriDataSource(cdn) }

        reads.read(DataSpec.Builder().setUri(API_URL).setLength(HALF.toLong()).build())
        val resumed = reads.read(DataSpec(API_URL))

        assertArrayEquals(bytes, resumed)
        assertEquals(listOf(0L to API_URL, HALF.toLong() to API_URL), cdn.opened.map { it.position to it.uri })
        assertNull(ContentMetadata.getRedirectedUri(cache.getContentMetadata(keys.buildCacheKey(DataSpec(API_URL)))))
        assertTrue(storedMetadata().none { it.contains(OAUTH_TOKEN) })
    }

    @Test
    fun storedSignedMetadataIsDroppedAndTheDownloadStaysReadable() {
        val key = keys.buildCacheKey(DataSpec(API_URL))
        val playlist = keys.buildCacheKey(DataSpec(PLAYLIST_URL))
        cacheFactory { RedirectingUpstream(bytes) }.read(DataSpec(API_URL))
        cacheFactory { ByteArrayDataSource(PLAYLIST) }.read(DataSpec(PLAYLIST_URL))
        cache.applyContentMetadataMutations(
            playlist,
            ContentMetadataMutations()
                .set("${ContentMetadata.KEY_CUSTOM_PREFIX}source", CDN_URL.toString())
                .set(KEPT, "plain"),
        )
        assertEquals(CDN_URL, ContentMetadata.getRedirectedUri(cache.getContentMetadata(key)))

        cache.dropSignedMetadata()
        cache.release()
        cache = SimpleCache(directory, NoOpCacheEvictor(), database)

        assertTrue(storedMetadata().none { OAUTH_TOKEN in it || ContentMetadata.KEY_REDIRECTED_URI in it })
        assertEquals(bytes.size.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(key)))
        assertEquals("plain", cache.getContentMetadata(playlist).get(KEPT, null as String?))
        val network = RedirectingUpstream(bytes)
        assertArrayEquals(bytes, cacheFactory { network }.read(DataSpec(API_URL)))
        assertTrue(network.opened.isEmpty())
    }

    private fun cacheFactory(upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(keys).setUpstreamDataSourceFactory(upstream)

    private fun storedMetadata(): List<String> {
        val db = database.readableDatabase
        val tables = db.rawQuery(INDEX_TABLES, null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertTrue(tables.isNotEmpty())
        return tables.flatMap { table ->
            db.rawQuery("SELECT metadata FROM $table", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(String(cursor.getBlob(0), Charsets.ISO_8859_1)) }
            }
        }
    }

    private fun DataSource.Factory.read(spec: DataSpec): ByteArray =
        DataSourceInputStream(createDataSource(), spec).use { it.readBytes() }

    private class RedirectingUpstream(private val content: ByteArray) : BaseDataSource(true) {
        val opened = mutableListOf<DataSpec>()
        private var position = 0
        private var remaining = 0

        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            transferInitializing(dataSpec)
            position = dataSpec.position.toInt()
            val length = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }?.toInt()
            remaining = length ?: (content.size - position)
            transferStarted(dataSpec)
            return remaining.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0) return C.RESULT_END_OF_INPUT
            val count = minOf(length, remaining)
            content.copyInto(buffer, offset, position, position + count)
            position += count
            remaining -= count
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri = CDN_URL

        override fun close() = Unit
    }

    private companion object {
        const val OAUTH_TOKEN = "oauth_token"
        const val KEPT = "${ContentMetadata.KEY_CUSTOM_PREFIX}kept"
        const val INDEX_TABLES =
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'ExoPlayerCacheIndex%'"
        const val ALICE = 7L
        const val HALF = 100_000
        val API_URL: Uri = "https://api.put.io/v2/files/9/stream".toUri()
        val PLAYLIST_URL: Uri = "https://api.put.io/v2/files/9/hls/media.m3u8".toUri()
        val PLAYLIST = "#EXTM3U".encodeToByteArray()
        val CDN_URL: Uri = "https://s1-cdn.put.io/stream/9?u=signed&oauth_token=secret".toUri()
    }
}
