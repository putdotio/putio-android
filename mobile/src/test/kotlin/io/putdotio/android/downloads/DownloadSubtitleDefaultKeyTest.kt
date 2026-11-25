package io.putdotio.android.downloads

import android.content.Context
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.DirectPlayDownload
import io.putdotio.android.playback.DirectPlayDownloadSource
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SOURCE_MKV
import io.putdotio.android.playback.inFileSubtitleLanguage
import io.putdotio.android.playback.toMediaItem
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.FileSubtitle
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DownloadSubtitleDefaultKeyTest {
    private val delegate = object : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> = error("Unexpected delegate call")

        override suspend fun startConversion(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> = error("Unexpected conversion call")

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
    }

    @Test
    fun decidedByMarksOnlyTheMatchingDefaultSubtitle() {
        val enFile = fileSubtitle("en-key", "eng", "https://api.put.io/v2/files/7/subtitles/en-key?oauth_token=secret")
        val trFile = fileSubtitle("tr-key", "tur", "https://api.put.io/v2/files/7/subtitles/tr-key?oauth_token=secret")
        val untrustedTr = fileSubtitle("tr-key", "tur", "https://subtitles.example.com/tr.srt")
        val queued = DownloadEntry(
            fileId = FilesItemId(7L),
            name = "Sintel.mkv",
            type = PutioFileType.VIDEO,
            artifact = DownloadArtifact.MP4,
            status = DownloadStatus.Queued,
            createdAt = 1L,
        )

        val decided = queued.decidedBy(
            DirectPlayDownload(
                DirectPlayDownloadSource.Original,
                listOf(enFile, trFile),
                defaultSubtitleKey = "tr-key",
            ),
        )
        assertEquals(listOf("en-key" to false, "tr-key" to true), decided.subtitles.map { it.key to it.isDefault })

        val withoutDefault = queued.decidedBy(
            DirectPlayDownload(DirectPlayDownloadSource.Original, listOf(enFile, trFile), defaultSubtitleKey = null),
        )
        assertEquals(
            listOf("en-key" to false, "tr-key" to false),
            withoutDefault.subtitles.map { it.key to it.isDefault },
        )

        val droppedDefault = queued.decidedBy(
            DirectPlayDownload(
                DirectPlayDownloadSource.Original,
                listOf(enFile, untrustedTr),
                defaultSubtitleKey = "tr-key",
            ),
        )
        assertEquals(listOf("en-key" to false), droppedDefault.subtitles.map { it.key to it.isDefault })

        val duplicateDefault = queued.decidedBy(
            DirectPlayDownload(
                DirectPlayDownloadSource.Original,
                listOf(trFile, trFile.copy(language = "Turkish 2")),
                defaultSubtitleKey = "tr-key",
            ),
        )
        assertEquals(
            listOf("tr-key" to true, "tr-key" to false),
            duplicateDefault.subtitles.map { it.key to it.isDefault },
        )
    }

    @Test
    fun subtitleJsonRoundTripsIsDefaultAndReadsLegacyEntriesAsFalse() {
        val en = downloadSubtitle("en-key", "eng", isDefault = false)
        val trDefault = downloadSubtitle("tr-key", "tur", isDefault = true)

        val json = listOf(en, trDefault).toJson()
        assertFalse(json.getJSONObject(0).has("isDefault"))
        assertTrue(json.getJSONObject(1).optBoolean("isDefault"))
        assertEquals(listOf(en, trDefault), json.toDownloadSubtitles())

        val roundTripped = downloadRequestData("Sintel.mkv", listOf(en, trDefault)).toDownloadRequestData().subtitles
        assertEquals(listOf(en, trDefault), roundTripped)

        val legacy = JSONArray(LEGACY_JSON).toDownloadSubtitles()
        assertEquals(listOf(en), legacy)
        assertFalse(legacy.single().isDefault)
    }

    @Test
    fun offlinePlaybackRestoresDefaultKeyOnlyWhileCachedAndKeepsLegacyNull() = runBlocking {
        val en = downloadSubtitle("en-key", "eng", isDefault = false)
        val trDefault = downloadSubtitle("tr-key", "tur", isDefault = true)
        val target = PlaybackTarget(FilesItemId(7L), "Sintel.mkv")
        val entry = DownloadEntry(
            target.fileId,
            target.name,
            PutioFileType.VIDEO,
            DownloadArtifact.MP4,
            DownloadStatus.Completed(1L),
            createdAt = 0L,
        )
        val downloads = MutableStateFlow(DownloadsState().withEntries(listOf(entry)))
        val streamUrl = "https://api.put.io/v2/files/7/mp4/stream"
        fun repo(subtitles: List<DownloadSubtitle>) =
            OfflinePlaybackRepository(downloads, delegate, testDownloadFilesApi) {
                OfflineRequest(streamUrl, subtitles)
            }

        val cachedResult = repo(listOf(en, trDefault)).resolve(target, convertedOnly = true)
        val cached = (cachedResult as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready
        assertEquals("tr-key", (cached.source.subtitles as PlaybackSubtitles.Sidecar).defaultKey)
        val cachedConfigs = requireNotNull(cached.source.toMediaItem(target.name).localConfiguration)
            .subtitleConfigurations
        assertEquals(listOf(0, C.SELECTION_FLAG_DEFAULT), cachedConfigs.map { it.selectionFlags })

        val evictedResult = repo(listOf(en)).resolve(target, convertedOnly = true)
        val evicted = (evictedResult as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready
        assertNull((evicted.source.subtitles as PlaybackSubtitles.Sidecar).defaultKey)
        val evictedConfigs = requireNotNull(evicted.source.toMediaItem(target.name).localConfiguration)
            .subtitleConfigurations
        assertEquals(listOf(0), evictedConfigs.map { it.selectionFlags })

        val legacy = JSONArray(LEGACY_JSON).toDownloadSubtitles()
        val legacyResult = repo(legacy).resolve(target, convertedOnly = true)
        val legacyReady = (legacyResult as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready
        assertNull((legacyReady.source.subtitles as PlaybackSubtitles.Sidecar).defaultKey)
        val legacyConfigs = requireNotNull(legacyReady.source.toMediaItem(target.name).localConfiguration)
            .subtitleConfigurations
        assertEquals(listOf(0), legacyConfigs.map { it.selectionFlags })
    }

    @Test
    fun isOriginalDownloadUriAndDownloaderFactorySkipMkvSubtitlesOnlyForOriginal() {
        val id = FilesItemId(7L)
        val originalUrl = DownloadArtifact.ORIGINAL.apiUrl(id, testDownloadFilesApi)
        val mp4Url = DownloadArtifact.MP4.apiUrl(id, testDownloadFilesApi)
        val hlsUrl = DownloadArtifact.HLS.apiUrl(id, testDownloadFilesApi)
        assertTrue(isOriginalDownloadUri(originalUrl.toUri()))
        assertFalse(isOriginalDownloadUri(mp4Url.toUri()))
        assertFalse(isOriginalDownloadUri(hlsUrl.toUri()))

        val mkv = downloadSubtitle("mkv-tr", "tur", isDefault = true).copy(source = SOURCE_MKV)
        val en = downloadSubtitle("en-key", "eng", isDefault = false)
        val fetched = mutableListOf<String>()
        val context: Context = ApplicationProvider.getApplicationContext()
        val dir = File(context.cacheDir, "dl-sub-test-${UUID.randomUUID()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(dir, NoOpCacheEvictor(), database)
        try {
            val cacheFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setCacheKeyFactory(UserScopedCacheKeys(1L))
                .setUpstreamDataSourceFactory {
                    val upstream = ByteArrayDataSource(ByteArray(16))
                    object : DataSource by upstream {
                        override fun open(dataSpec: DataSpec): Long {
                            fetched += dataSpec.uri.toString()
                            return upstream.open(dataSpec)
                        }
                    }
                }
            val factory = UserScopedDownloaderFactory(
                { cacheFactory },
                Runnable::run,
                CompletableFuture.completedFuture(Unit),
            )
            val originalReq = DownloadRequest.Builder(downloadContentId(1L, id), originalUrl.toUri())
                .setMimeType(MimeTypes.VIDEO_UNKNOWN)
                .setData(downloadRequestData("Sintel.mkv", listOf(mkv, en)))
                .build()
            factory.createDownloader(originalReq).download(null)
            assertEquals(listOf(en.url, originalUrl), fetched)

            fetched.clear()
            val mp4Id = FilesItemId(8L)
            val mp4DownloadUrl = DownloadArtifact.MP4.apiUrl(mp4Id, testDownloadFilesApi)
            val mp4Mkv = mkv.copy(url = "https://api.put.io/v2/files/8/subtitles/mkv-tr")
            val mp4En = en.copy(url = "https://api.put.io/v2/files/8/subtitles/en-key")
            val mp4Req = DownloadRequest.Builder(downloadContentId(1L, mp4Id), mp4DownloadUrl.toUri())
                .setMimeType(MimeTypes.VIDEO_UNKNOWN)
                .setData(downloadRequestData("Sintel.mkv", listOf(mp4Mkv, mp4En)))
                .build()
            factory.createDownloader(mp4Req).download(null)
            assertEquals(listOf(mp4Mkv.url, mp4En.url, mp4DownloadUrl), fetched)
        } finally {
            cache.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun offlineOriginalWithMkvDefaultSetsInFileLanguageWithoutSidecarTrack() = runBlocking {
        val mkvDefault = downloadSubtitle("mkv-tr", "tur", isDefault = true).copy(source = SOURCE_MKV)
        val en = downloadSubtitle("en-key", "eng", isDefault = false)
        val target = PlaybackTarget(FilesItemId(7L), "Sintel.mkv", method = StreamingMethod.File)
        val entry = DownloadEntry(
            target.fileId,
            target.name,
            PutioFileType.VIDEO,
            DownloadArtifact.ORIGINAL,
            DownloadStatus.Completed(1L),
            createdAt = 0L,
        )
        val downloads = MutableStateFlow(DownloadsState().withEntries(listOf(entry)))
        val originalUrl = DownloadArtifact.ORIGINAL.apiUrl(target.fileId, testDownloadFilesApi)
        val repo = OfflinePlaybackRepository(downloads, delegate, testDownloadFilesApi) {
            OfflineRequest(originalUrl, listOf(mkvDefault, en))
        }

        val ready = (repo.resolve(target) as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready
        assertEquals(PlaybackSourceKind.ORIGINAL, ready.source.kind)
        val item = ready.source.toMediaItem(target.name)
        val player = object : SimpleBasePlayer(Looper.getMainLooper()) {
            override fun getState(): State = State.Builder()
                .setAvailableCommands(
                    Player.Commands.Builder()
                        .addAll(Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                        .build(),
                )
                .setPlaylist(listOf(MediaItemData.Builder("7").setMediaItem(item).build()))
                .setCurrentMediaItemIndex(0)
                .build()
        }
        assertEquals("tur", player.inFileSubtitleLanguage())
        val configs = requireNotNull(item.localConfiguration).subtitleConfigurations
        assertEquals(listOf("en-key"), configs.map { it.id })
        assertEquals(listOf(0), configs.map { it.selectionFlags })
    }

    private fun fileSubtitle(key: String, languageCode: String, url: String) =
        FileSubtitle(
            format = "srt",
            key = key,
            language = languageCode,
            languageCode = languageCode,
            name = "$key.srt",
            source = "opensubtitles",
            url = url,
        )

    private fun downloadSubtitle(key: String, languageCode: String, isDefault: Boolean) =
        DownloadSubtitle(
            key = key,
            url = "https://api.put.io/v2/files/7/subtitles/$key",
            format = "srt",
            language = "English",
            languageCode = languageCode,
            name = "$key.srt",
            source = "opensubtitles",
            isDefault = isDefault,
        )

    private companion object {
        const val LEGACY_JSON =
            """[{"key":"en-key","url":"https://api.put.io/v2/files/7/subtitles/en-key",""" +
                """"format":"srt","language":"English","languageCode":"eng",""" +
                """"name":"en-key.srt","source":"opensubtitles"}]"""
    }
}
