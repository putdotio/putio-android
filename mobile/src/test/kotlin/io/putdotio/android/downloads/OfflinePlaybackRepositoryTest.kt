package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import io.putdotio.android.PutioFailure

class OfflinePlaybackRepositoryTest {
    private val target = PlaybackTarget(FilesItemId(7L), "Sintel.mkv")
    private val delegateCalls = mutableListOf<PlaybackTarget>()
    private val delegate = object : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
            delegateCalls += target
            return PlaybackRepositoryResult.Failure(
                PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline"))),
            )
        }

        override suspend fun startConversion(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
            delegateCalls += target
            return PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(PlaybackConversionState.Queued))
        }

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
    }

    @Test
    fun completedDownloadResolvesLocallyWithoutTouchingTheNetwork() = runBlocking {
        val downloads = MutableStateFlow(DownloadsState().withEntries(listOf(
            DownloadEntry(
                target.fileId, target.name, PutioFileType.VIDEO, DownloadArtifact.HLS, DownloadStatus.Completed(1L), 0L,
            ),
        )))
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
        val downloads = MutableStateFlow(DownloadsState().withEntries(listOf(
            DownloadEntry(
                target.fileId, target.name, PutioFileType.VIDEO, DownloadArtifact.HLS, DownloadStatus.Completed(1L), 0L,
            ),
        )))
        // A download from before the URL gained max_subtitle_count is cached under its own URL.
        val earlier = "https://api.put.io/v2/files/7/hls/media.m3u8?subtitle_key=all"
        val replayed = OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of) { earlier }
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
        val downloads = MutableStateFlow(DownloadsState().withEntries(listOf(entry)))
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
        val downloads = MutableStateFlow(DownloadsState().withEntries(listOf(completed().copy(subtitlesHidden = true))))
        val repository = OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of)

        val ready = (repository.resolve(target) as PlaybackRepositoryResult.Success).value as PlaybackResolution.Ready

        assertTrue(ready.subtitlesHidden)
        assertEquals(PlaybackSubtitles.None, ready.source.subtitles)
        assertTrue(ready.source.url.value.endsWith("max_subtitle_count=0"))
    }

    @Test
    fun aCopyWhoseBytesAreGoneStreamsInstead() = runBlocking {
        val downloads = MutableStateFlow(DownloadsState().withEntries(listOf(completed())))
        val repository = OfflinePlaybackRepository(
            downloads, delegate, PutioCredentialUrl::of, localCopyAvailable = { false },
        )

        assertTrue(repository.resolve(target) is PlaybackRepositoryResult.Failure)
        assertEquals(listOf(target), delegateCalls)
    }

    @Test
    fun anythingElseStreamsThroughTheDelegate() = runBlocking {
        val downloads = MutableStateFlow(DownloadsState().withEntries(listOf(
            DownloadEntry(
                target.fileId,
                target.name,
                PutioFileType.VIDEO,
                DownloadArtifact.HLS,
                DownloadStatus.Downloading(1L, 50f),
                0L,
            ),
        )))
        val repository = OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of)
        assertTrue(repository.resolve(target) is PlaybackRepositoryResult.Failure)
        assertTrue(repository.resolve(target.copy(fileId = FilesItemId(8L))) is PlaybackRepositoryResult.Failure)
        assertEquals(2, delegateCalls.size)
    }

    private fun completed() = DownloadEntry(
        target.fileId, target.name, PutioFileType.VIDEO, DownloadArtifact.HLS, DownloadStatus.Completed(1L), 0L,
    )

    @Test
    fun theViewersConvertStartsThroughTheDelegate() = runBlocking {
        val repository = OfflinePlaybackRepository(MutableStateFlow(DownloadsState()), delegate, PutioCredentialUrl::of)
        assertEquals(
            PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(PlaybackConversionState.Queued)),
            repository.startConversion(target),
        )
        assertEquals(listOf(target), delegateCalls)
    }
}
