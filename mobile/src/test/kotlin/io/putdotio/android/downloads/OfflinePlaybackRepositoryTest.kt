package io.putdotio.android.downloads

import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackStreamFormat
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlaybackRepositoryTest {
    private val target = PlaybackTarget(FilesItemId(7L), "Sintel.mkv")
    private val delegateCalls = mutableListOf<PlaybackTarget>()
    private val convertedOnlyCalls = mutableListOf<Boolean>()
    private val delegate = object : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> {
            convertedOnlyCalls += convertedOnly
            delegateCalls += target
            return PlaybackRepositoryResult.Failure(
                PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline"))),
            )
        }

        override suspend fun startConversion(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            error("No conversion expected")

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
    }

    @Test
    fun completedDownloadResolvesLocallyWithoutTouchingTheNetwork() = runBlocking {
        val downloads = downloadsOf(completed())
        val repository = OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of)
        val result = repository.resolve(target) as PlaybackRepositoryResult.Success
        val ready = result.value as PlaybackResolution.Ready
        assertEquals(PlaybackSourceKind.HLS, ready.source.kind)
        assertEquals(PlaybackSubtitles.Embedded, ready.source.subtitles)
        assertEquals("/v2/files/7/hls/media.m3u8", ready.source.url.encodedPath)
        assertFalse(ready.source.url.queryParameterNames.contains("oauth_token"))
        assertFalse(ready.useStartFrom)
        assertTrue(delegateCalls.isEmpty())
    }

    @Test
    fun completedDownloadReplaysTheUrlItWasRequestedWith() = runBlocking {
        val downloads = downloadsOf(completed())
        // A download from before the URL gained max_subtitle_count is cached under its own URL.
        val earlier = "https://api.put.io/v2/files/7/hls/media.m3u8?subtitle_key=all"
        val replayed =
            OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of) { OfflineRequest(earlier) }
        val unknown = OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of) { null }

        val ready = (replayed.resolve(target) as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready
        val fallback = (unknown.resolve(target) as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready

        assertEquals(earlier, ready.source.url.value)
        assertEquals(DownloadArtifact.HLS.apiUrl(target.fileId), fallback.source.url.value)
        assertTrue(delegateCalls.isEmpty())
    }

    @Test
    fun offlinePlaybackResumesFromThePositionThisDeviceKeptWhenResumeIsOn() = runBlocking {
        val entry = completed().copy(startFromSeconds = 100.0, durationSeconds = 1_800.0)
        val downloads = downloadsOf(entry)
        val resume = object : OfflineResume {
            override fun enabled() = true

            override suspend fun position(entry: DownloadEntry) = 250.0
        }
        val repository = OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of, resume = resume)

        val ready = (repository.resolve(target) as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready

        assertTrue(ready.useStartFrom)
        assertEquals(250.0, ready.source.startFromSeconds, 0.0)
        // The resume prompt's finished-video rule needs the duration the row kept.
        assertEquals(1_800.0, ready.durationSeconds)
        assertTrue(delegateCalls.isEmpty())
    }

    @Test
    fun aHideSubtitlesDownloadCarriesNoSubtitlesAndHidesThePickerOnAColdOfflineStart() = runBlocking {
        val downloads = downloadsOf(completed().copy(subtitlesHidden = true))
        val repository = OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of)

        val ready = (repository.resolve(target) as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready

        assertTrue(ready.subtitlesHidden)
        assertEquals(PlaybackSubtitles.None, ready.source.subtitles)
        assertTrue(ready.source.url.value.endsWith("max_subtitle_count=0"))
    }

    @Test
    fun aCopyWhoseBytesAreGoneStreamsInstead() = runBlocking {
        val downloads = downloadsOf(completed())
        val repository = OfflinePlaybackRepository(
            downloads, delegate, PutioCredentialUrl::of, localCopyAvailable = { false },
        )

        assertTrue(repository.resolve(target) is PlaybackRepositoryResult.Failure)
        assertEquals(listOf(target), delegateCalls)
    }

    @Test
    fun anythingElseStreamsThroughTheDelegate() = runBlocking {
        val downloads = downloadsOf(completed().copy(status = DownloadStatus.Downloading(1L, 50f)))
        val repository = OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of)
        assertTrue(repository.resolve(target) is PlaybackRepositoryResult.Failure)
        assertTrue(repository.resolve(target.copy(fileId = FilesItemId(8L))) is PlaybackRepositoryResult.Failure)
        assertEquals(2, delegateCalls.size)
    }

    @Test
    fun convertedOnlyStreamsInPlaceOfAnOriginalVideoDownload() = runBlocking {
        val original = OfflinePlaybackRepository(
            downloadsOf(completed().copy(artifact = DownloadArtifact.ORIGINAL)), delegate, PutioCredentialUrl::of,
        )
        val hls = OfflinePlaybackRepository(downloadsOf(completed()), delegate, PutioCredentialUrl::of)

        assertTrue(original.resolve(target) is PlaybackRepositoryResult.Success)
        assertTrue(original.resolve(target, convertedOnly = true) is PlaybackRepositoryResult.Failure)
        assertTrue(hls.resolve(target, convertedOnly = true) is PlaybackRepositoryResult.Success)
        assertEquals(listOf(true), convertedOnlyCalls)
    }

    @Test
    fun anOriginalOverHlsPlaysOfflineAsAnOriginalSourceAndConvertedOnlyStreams() = runBlocking {
        val repository = OfflinePlaybackRepository(
            downloadsOf(completed().copy(artifact = DownloadArtifact.ORIGINAL_HLS)), delegate, PutioCredentialUrl::of,
        )

        val ready = (repository.resolve(target) as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready

        assertEquals(PlaybackSourceKind.ORIGINAL, ready.source.kind)
        assertEquals(PlaybackStreamFormat.HLS, ready.source.format)
        assertEquals(PlaybackSubtitles.Embedded, ready.source.subtitles)
        assertEquals(
            "https://api.put.io/v2/files/7/hls/media.m3u8?subtitle_key=all&max_subtitle_count=-1&original=1",
            ready.source.url.value,
        )
        assertTrue(ready.offline)
        assertTrue(delegateCalls.isEmpty())
        assertTrue(repository.resolve(target, convertedOnly = true) is PlaybackRepositoryResult.Failure)
        assertEquals(listOf(true), convertedOnlyCalls)
    }

    @Test
    fun aDirectPlayDownloadPlaysItsFileFromTheCacheWithItsCachedSubtitleFiles() = runBlocking {
        val english = DownloadSubtitle(
            "en-key", "https://api.put.io/v2/files/7/subtitles/en-key", "srt", "English", "eng", "Sintel.en.srt",
            "opensubtitles",
        )
        val converted = completed().copy(artifact = DownloadArtifact.MP4, subtitles = listOf(english))
        val mp4Url = "https://api.put.io/v2/files/7/mp4/stream"
        val repository = OfflinePlaybackRepository(downloadsOf(converted), delegate, PutioCredentialUrl::of) {
            OfflineRequest(mp4Url, listOf(english))
        }

        val ready = (repository.resolve(target, convertedOnly = true) as PlaybackRepositoryResult.Success).value
            as PlaybackResolution.Ready
        assertEquals(PlaybackSourceKind.MP4, ready.source.kind)
        assertEquals(PlaybackStreamFormat.PROGRESSIVE, ready.source.format)
        assertEquals(mp4Url, ready.source.url.value)
        val sidecar = (ready.source.subtitles as PlaybackSubtitles.Sidecar).tracks.single()
        assertEquals(english.url, sidecar.url.value)
        assertEquals(
            listOf("en-key", "srt", "eng", "Sintel.en.srt"),
            with(sidecar) { listOf(key, format, languageCode, name) },
        )
        assertTrue(ready.offline)

        val original = OfflinePlaybackRepository(
            downloadsOf(converted.copy(artifact = DownloadArtifact.ORIGINAL)), delegate, PutioCredentialUrl::of,
        ) { OfflineRequest("https://api.put.io/v2/files/7/stream", listOf(english)) }
        val originalSource = ((original.resolve(target) as PlaybackRepositoryResult.Success).value
            as PlaybackResolution.Ready).source
        assertEquals(PlaybackSourceKind.ORIGINAL, originalSource.kind)
        assertEquals(PlaybackStreamFormat.PROGRESSIVE, originalSource.format)
        assertEquals(listOf("en-key"), (originalSource.subtitles as PlaybackSubtitles.Sidecar).tracks.map { it.key })

        val bare = OfflinePlaybackRepository(downloadsOf(converted), delegate, PutioCredentialUrl::of) {
            OfflineRequest(mp4Url)
        }
        val bareSource = ((bare.resolve(target) as PlaybackRepositoryResult.Success).value
            as PlaybackResolution.Ready).source
        assertEquals(PlaybackSubtitles.None, bareSource.subtitles)
        assertTrue(delegateCalls.isEmpty())
    }

    @Test
    fun castingADirectPlayDownloadStreamsStandardFromPutio() = runBlocking {
        val converted = completed().copy(artifact = DownloadArtifact.MP4)
        val repository = OfflinePlaybackRepository(downloadsOf(converted), delegate, PutioCredentialUrl::of)
        val cast = target.copy(method = StreamingMethod.Hls)

        repository.resolve(cast, convertedOnly = true)

        assertEquals(listOf(cast), delegateCalls)
        assertEquals(listOf(true), convertedOnlyCalls)
    }

    @Test
    fun playWithTheOtherMethodStreamsInPlaceOfADownloadMadeWithTheFirst() = runBlocking {
        val standard = OfflinePlaybackRepository(downloadsOf(completed()), delegate, PutioCredentialUrl::of)
        val direct = OfflinePlaybackRepository(
            downloadsOf(completed().copy(artifact = DownloadArtifact.ORIGINAL)), delegate, PutioCredentialUrl::of,
        )
        val playWithDirect = target.copy(method = StreamingMethod.File)
        val playWithStandard = target.copy(method = StreamingMethod.Hls)

        assertTrue(standard.resolve(playWithDirect) is PlaybackRepositoryResult.Failure)
        assertTrue(direct.resolve(playWithStandard) is PlaybackRepositoryResult.Failure)
        assertEquals(listOf(playWithDirect, playWithStandard), delegateCalls)

        assertTrue(standard.resolve(playWithStandard) is PlaybackRepositoryResult.Success)
        assertTrue(direct.resolve(playWithDirect) is PlaybackRepositoryResult.Success)
        assertEquals(2, delegateCalls.size)
    }

    private fun completed() = DownloadEntry(
        target.fileId, target.name, PutioFileType.VIDEO, DownloadArtifact.HLS, DownloadStatus.Completed(1L), 0L,
    )

    private fun downloadsOf(vararg entries: DownloadEntry) =
        MutableStateFlow(DownloadsState().withEntries(entries.toList()))
}
