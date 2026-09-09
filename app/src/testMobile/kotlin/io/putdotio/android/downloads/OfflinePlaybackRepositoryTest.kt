package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
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

class OfflinePlaybackRepositoryTest {
    private val target = PlaybackTarget(FilesItemId(7L), "Sintel.mkv")
    private val delegateCalls = mutableListOf<PlaybackTarget>()
    private val delegate = object : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
            delegateCalls += target
            return PlaybackRepositoryResult.Failure(PlaybackFailure.NetworkUnavailable(IOException("offline")))
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
    fun anythingElseStreamsThroughTheDelegate() = runBlocking {
        val downloads = MutableStateFlow(DownloadsState().withEntries(listOf(
            DownloadEntry(
                target.fileId, target.name, PutioFileType.VIDEO, DownloadArtifact.HLS, DownloadStatus.Downloading(1L, 2L), 0L,
            ),
        )))
        val repository = OfflinePlaybackRepository(downloads, delegate, PutioCredentialUrl::of)
        assertTrue(repository.resolve(target) is PlaybackRepositoryResult.Failure)
        assertTrue(repository.resolve(target.copy(fileId = FilesItemId(8L))) is PlaybackRepositoryResult.Failure)
        assertEquals(2, delegateCalls.size)
    }
}
