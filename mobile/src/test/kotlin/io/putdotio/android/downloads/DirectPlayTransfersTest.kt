package io.putdotio.android.downloads

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Direct play transfers against Media3's own request, index and cache types; only the bytes are fed directly. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DirectPlayTransfersTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences("direct-play-test", Context.MODE_PRIVATE)
    private val downloadManager = DownloadManager(
        context,
        DefaultDownloadIndex(StandaloneDatabaseProvider(context), "DirectPlayTest"),
    ) { error("No transfer runs here") }
    private val cachedKeys = mutableListOf<String>()

    @After
    fun tearDown() {
        downloadManager.release()
        downloadPreferences(context).edit().clear().commit()
        cachedKeys.forEach(MobileDownloadCache.get(context).cache::removeResource)
        MobileDownloadCache.get(context).downloadManager.downloadIndex
            .let { it as WritableDownloadIndex }.removeDownload(downloadContentId(USER, FilesItemId(VIDEO)))
    }

    @Test
    fun eachArtifactAsksForItsFileAndAVideoFileCarriesItsTypeAndSubtitles() {
        val original = request(entry(DownloadArtifact.ORIGINAL, listOf(ENGLISH)))
        val converted = request(entry(DownloadArtifact.MP4))
        val hls = request(entry(DownloadArtifact.HLS))
        val audio = request(entry(DownloadArtifact.ORIGINAL).copy(type = PutioFileType.AUDIO))

        assertEquals("https://api.put.io/v2/files/7/stream", original.uri.toString())
        assertEquals("https://api.put.io/v2/files/7/mp4/stream", converted.uri.toString())
        assertEquals(
            listOf(MimeTypes.VIDEO_UNKNOWN, MimeTypes.VIDEO_UNKNOWN, MimeTypes.APPLICATION_M3U8, null),
            listOf(original, converted, hls, audio).map { it.mimeType },
        )
        assertEquals(
            listOf(
                DownloadArtifact.ORIGINAL to PutioFileType.VIDEO,
                DownloadArtifact.MP4 to PutioFileType.VIDEO,
                DownloadArtifact.HLS to PutioFileType.VIDEO,
                DownloadArtifact.ORIGINAL to PutioFileType.AUDIO,
            ),
            listOf(original, converted, hls, audio).map { it.artifact to it.fileType },
        )
        val data = original.data.toDownloadRequestData()
        assertEquals("Sintel.mkv" to listOf(ENGLISH), data.name to data.subtitles)
        assertEquals("Sintel.mkv", hls.data.decodeToString())
    }

    @Test
    fun aStoredRowKeepsItsSubtitleFiles() = runBlocking {
        val row = entry(DownloadArtifact.ORIGINAL, listOf(ENGLISH, ENGLISH.copy(key = "fr", format = null)))
        MobileDownloadStore(preferences, "user-$USER", Dispatchers.Unconfined).upsert(row)

        val reread = MobileDownloadStore(preferences, "user-$USER", Dispatchers.Unconfined).find(row.fileId)
        assertEquals(row, reread)
    }

    @Test
    fun anOriginalVideoRefusedBeforeItsFirstByteDownloadsTheConvertedFileWithItsSubtitles() = runBlocking {
        val store = MobileDownloadStore(preferences, "user-$USER", Dispatchers.Unconfined)
        val original = entry(DownloadArtifact.ORIGINAL, listOf(ENGLISH)).copy(accepted = false)
        store.upsert(original)
        val listener = MobileDownloadNotifications(context, stores = { store }) { USER }

        listener.onDownloadChanged(downloadManager, failed(request(original), bytes = 0L), rejected(HTTP_NOT_FOUND))

        val sent = generateSequence { shadowOf(context).nextStartedService }
            .filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }.toList().single()
        val resent = checkNotNull(
            IntentCompat.getParcelableExtra(sent, DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java),
        )
        assertEquals("https://api.put.io/v2/files/7/mp4/stream", resent.uri.toString())
        assertEquals(listOf(ENGLISH), resent.data.toDownloadRequestData().subtitles)
        val row = store.find(original.fileId)
        assertEquals(DownloadArtifact.MP4 to DownloadStatus.Queued, row?.artifact to row?.status)
        assertEquals(listOf(ENGLISH), row?.subtitles)
        assertEquals(false, row?.awaitsDirectPlay)
    }

    @Test
    fun onlyAnUntouchedVideoOriginalFallsBack() = runBlocking {
        val video = request(entry(DownloadArtifact.ORIGINAL))
        val audio = request(entry(DownloadArtifact.ORIGINAL).copy(type = PutioFileType.AUDIO))

        assertTrue(failed(video, bytes = 0L).fallingBackToConverted(rejected(HTTP_NOT_FOUND)))
        assertFalse(failed(video, bytes = 10L).fallingBackToConverted(rejected(HTTP_NOT_FOUND)))
        assertFalse(failed(video, bytes = 0L).fallingBackToConverted(UnknownHostException("offline")))
        assertFalse(failed(audio, bytes = 0L).fallingBackToConverted(rejected(HTTP_NOT_FOUND)))
        val converted = request(entry(DownloadArtifact.MP4))
        assertFalse(failed(converted, bytes = 0L).fallingBackToConverted(rejected(HTTP_NOT_FOUND)))
    }

    @Test
    fun offlinePlaybackOffersOnlyTheSubtitleFilesOnDisk() {
        val french = ENGLISH.copy(key = "fr", url = "https://api.put.io/v2/files/7/subtitles/fr")
        val downloads = MobileDownloadCache.get(context)
        val index = downloads.downloadManager.downloadIndex as WritableDownloadIndex
        index.putDownload(
            Download(
                request(entry(DownloadArtifact.ORIGINAL, listOf(ENGLISH, french))),
                Download.STATE_COMPLETED, 1L, 1L, 4L, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE,
                DownloadProgress(),
            ),
        )
        cache(downloads, ENGLISH.url)

        val offline = downloads.offlineRequest(USER, FilesItemId(VIDEO))

        assertEquals(OfflineRequest("https://api.put.io/v2/files/7/stream", listOf(ENGLISH)), offline)
        assertNull(downloads.offlineRequest(OTHER, FilesItemId(VIDEO)))
    }

    @Test
    fun subtitleFilesCacheBeforeTheVideoAndARefusedOneIsLeftOut() {
        val steps = mutableListOf<String>()
        val video = ScriptedDownloader("video", steps)
        val refused = ScriptedDownloader("refused", steps, rejected(HTTP_NOT_FOUND))
        val english = ScriptedDownloader("english", steps)
        val downloader = SubtitledDownloader(video, listOf(refused, english))
        var progress: Long? = null

        downloader.download { _, bytes, _ -> progress = bytes }
        downloader.remove()

        assertEquals(
            listOf("refused", "english", "video", "remove video", "remove refused", "remove english"),
            steps,
        )
        assertEquals(VIDEO_BYTES, progress)
    }

    @Test
    fun aSubtitleCutOffByTheNetworkFailsTheTransferSoMedia3RetriesItAndACancelStopsIt() {
        val steps = mutableListOf<String>()
        val offline = ScriptedDownloader("subtitle", steps, UnknownHostException("offline"))
        try {
            SubtitledDownloader(ScriptedDownloader("video", steps), listOf(offline)).download(null)
            fail("A network failure must reach Media3")
        } catch (_: UnknownHostException) {
        }
        val cancelled = SubtitledDownloader(ScriptedDownloader("video", steps), emptyList()).apply { cancel() }
        try {
            cancelled.download(null)
            fail("A cancelled transfer must not run")
        } catch (_: InterruptedException) {
        }
        assertEquals(listOf("subtitle"), steps)
    }

    private fun cache(downloads: MobileDownloadCache, url: String) {
        val keys = UserScopedCacheKeys(USER)
        val writer = CacheDataSource.Factory().setCache(downloads.cache).setCacheKeyFactory(keys)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(SUBTITLE_BYTES) }
            .createDataSource()
        CacheWriter(writer, DataSpec(Uri.parse(url)), null, null).cache()
        cachedKeys += keys.buildCacheKey(DataSpec(Uri.parse(url)))
    }

    private fun request(entry: DownloadEntry): DownloadRequest {
        var sent: DownloadRequest? = null
        sendDownloadRequest(context, USER, entry) { request, _ -> sent = request }
        return checkNotNull(sent)
    }

    private fun entry(artifact: DownloadArtifact, subtitles: List<DownloadSubtitle> = emptyList()) = DownloadEntry(
        FilesItemId(VIDEO), "Sintel.mkv", PutioFileType.VIDEO, artifact, DownloadStatus.Queued,
        createdAt = 1L, accepted = true, subtitles = subtitles,
    )

    private fun failed(request: DownloadRequest, bytes: Long) = Download(
        request,
        Download.STATE_FAILED,
        0L,
        0L,
        0L,
        Download.STOP_REASON_NONE,
        Download.FAILURE_REASON_UNKNOWN,
        DownloadProgress().also { it.bytesDownloaded = bytes },
    )

    private fun rejected(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code, null, null, emptyMap(), DataSpec(Uri.EMPTY), ByteArray(0),
    )

    private class ScriptedDownloader(
        private val name: String,
        private val steps: MutableList<String>,
        private val failure: Exception? = null,
    ) : Downloader {
        override fun download(progressListener: Downloader.ProgressListener?) {
            steps += name
            failure?.let { throw it }
            progressListener?.onProgress(VIDEO_BYTES, VIDEO_BYTES, PERCENT)
        }

        override fun cancel() {
            steps += "cancel $name"
        }

        override fun remove() {
            steps += "remove $name"
        }
    }

    private companion object {
        const val USER = 7L
        const val OTHER = 8L
        const val VIDEO = 7L
        const val HTTP_NOT_FOUND = 404
        const val VIDEO_BYTES = 1_000L
        const val PERCENT = 100f
        val SUBTITLE_BYTES = "1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray()
        val ENGLISH = DownloadSubtitle(
            key = "en-key",
            url = "https://api.put.io/v2/files/7/subtitles/en-key",
            format = "srt",
            language = "English",
            languageCode = "eng",
            name = "Sintel.en.srt",
            source = "opensubtitles",
        )
    }
}
