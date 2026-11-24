package io.putdotio.android.downloads

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.DirectPlayDownload
import io.putdotio.android.playback.DirectPlayDownloadSource
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.toMediaItem
import io.putdotio.sdk.files.FileSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioFileType
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
