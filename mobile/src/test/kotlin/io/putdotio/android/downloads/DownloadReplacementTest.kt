package io.putdotio.android.downloads

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DownloadReplacementTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val index = DefaultDownloadIndex(StandaloneDatabaseProvider(context), "ReplacementTest")
    private val preferences = context.getSharedPreferences("replacement-test", Context.MODE_PRIVATE)
    private val store = MobileDownloadStore(preferences, "user-$USER", Dispatchers.Unconfined)
    private val cachedKeys = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val managers = mutableListOf<DownloadManager>()
    private val engines = mutableListOf<MobileDownloadEngine>()

    @After
    fun tearDown() {
        engines.forEach { it.close() }
        managers.forEach { it.release() }
        scope.cancel()
        downloadPreferences(context).edit().clear().commit()
        cachedKeys.forEach(MobileDownloadCache.get(context).cache::removeResource)
    }

    @Test
    fun aStartForAnotherFileOrOtherSubtitlesDropsTheHeldBytesAndSubtitleFilesFirst() {
        val missing = entry(10L, DownloadArtifact.ORIGINAL_HLS, DownloadStatus.Missing)
        val original = entry(11L, DownloadArtifact.ORIGINAL, NO_BYTES, listOf(ENGLISH))
        val partial = entry(12L, DownloadArtifact.MP4, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 10L))
        index.putDownload(record(missing, Download.STATE_COMPLETED))
        index.putDownload(record(original, Download.STATE_FAILED))
        index.putDownload(record(partial, Download.STATE_FAILED))
        val held = listOf(request(missing).uri.toString(), request(original).uri.toString(), ENGLISH.url)
        for (url in held + request(partial).uri.toString()) cache(url)
        runBlocking { for (row in listOf(missing, original, partial)) store.upsert(row) }
        val engine = engine()

        runBlocking {
            engine.start(missing.copy(artifact = DownloadArtifact.MP4, status = DownloadStatus.Queued))
            engine.start(original.copy(subtitles = listOf(FRENCH), status = DownloadStatus.Queued))
            engine.start(partial.copy(status = DownloadStatus.Queued))
        }

        assertEquals(listOf(false, false, false), held.map(::cached))
        assertTrue(cached(request(partial).uri.toString()))
        assertEquals(
            listOf(
                "$USER:10 https://api.put.io/v2/files/10/mp4/stream []",
                "$USER:11 https://api.put.io/v2/files/11/stream [fr-key]",
                "$USER:12 https://api.put.io/v2/files/12/mp4/stream []",
            ),
            sentAdds().map { "${it.id} ${it.uri} ${it.data.toDownloadRequestData().subtitles.map { s -> s.key }}" },
        )
    }

    @Test
    fun aDirectPlayVideoLostMidLookupIsLeftForTheControllersLookup() {
        runBlocking {
            store.upsert(entry(20L, DownloadArtifact.MP4, DownloadStatus.Queued).copy(accepted = false))
            store.upsert(entry(21L, DownloadArtifact.HLS, DownloadStatus.Queued).copy(accepted = false))
        }

        engine()

        assertEquals(listOf("$USER:21"), sentAdds().map { it.id })
    }

    private fun engine(): MobileDownloadEngine {
        val manager = DownloadManager(context, index) { error("No transfer runs here") }.apply {
            requirements = Requirements(0)
            managers += this
        }
        return MobileDownloadEngine(context, store, USER, scope, manager, Dispatchers.Unconfined).also { engines += it }
    }

    private fun sentAdds(): List<DownloadRequest> =
        generateSequence { shadowOf(context).nextStartedService }
            .filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
            .mapNotNull {
                IntentCompat.getParcelableExtra(it, DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java)
            }
            .toList()

    private fun cache(url: String) {
        val downloads = MobileDownloadCache.get(context)
        CacheWriter(
            CacheDataSource.Factory().setCache(downloads.cache).setCacheKeyFactory(KEYS)
                .setUpstreamDataSourceFactory { ByteArrayDataSource(ByteArray(CACHED_BYTES)) }.createDataSource(),
            DataSpec(Uri.parse(url)),
            null,
            null,
        ).cache()
        cachedKeys += KEYS.buildCacheKey(DataSpec(Uri.parse(url)))
    }

    private fun cached(url: String): Boolean {
        val key = KEYS.buildCacheKey(DataSpec(Uri.parse(url)))
        return MobileDownloadCache.get(context).cache.getCachedSpans(key).isNotEmpty()
    }

    private fun request(entry: DownloadEntry): DownloadRequest {
        var sent: DownloadRequest? = null
        sendDownloadRequest(context, USER, entry) { request, _ -> sent = request }
        return checkNotNull(sent)
    }

    private fun record(entry: DownloadEntry, state: Int) = Download(
        request(entry),
        state,
        0L,
        0L,
        TOTAL,
        Download.STOP_REASON_NONE,
        if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE,
        DownloadProgress(),
    )

    private fun entry(
        fileId: Long,
        artifact: DownloadArtifact,
        status: DownloadStatus,
        subtitles: List<DownloadSubtitle> = emptyList(),
    ) = DownloadEntry(
        FilesItemId(fileId), "file-$fileId.mkv", PutioFileType.VIDEO, artifact, status,
        createdAt = fileId, accepted = true, subtitles = subtitles,
    )

    private companion object {
        const val USER = 7L
        const val TOTAL = 1_000L
        const val CACHED_BYTES = 4
        val KEYS = UserScopedCacheKeys(USER)
        val NO_BYTES = DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L)
        val ENGLISH = DownloadSubtitle(
            key = "en-key",
            url = "https://api.put.io/v2/files/11/subtitles/en-key",
            format = "srt",
            language = "English",
            languageCode = "eng",
            name = "Sintel.en.srt",
            source = "opensubtitles",
        )
        val FRENCH = ENGLISH.copy(key = "fr-key", url = "https://api.put.io/v2/files/11/subtitles/fr-key")
    }
}
