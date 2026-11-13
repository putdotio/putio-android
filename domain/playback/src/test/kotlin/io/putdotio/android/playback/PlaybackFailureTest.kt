package io.putdotio.android.playback

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Player errors by type, and which of them a retry can fix. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackFailureTest {
    @Test
    fun mediaTheDeviceCannotPlayIsUnsupportedAndNotRetried() {
        listOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ).forEach { code ->
            val failure = PlaybackException("unsupported", null, code).toPlaybackFailure()
            assertTrue("$code: $failure", failure is PlaybackFailure.MediaUnsupported)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun aDecoderThatFailsToStartMayBeBusyAndIsRetried() {
        val failure = PlaybackException("busy", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
            .toPlaybackFailure()
        assertTrue(failure.putioFailure is PutioFailure.Unexpected)
        assertTrue(failure.retryable)
    }

    @Test
    fun anExpiredMediaLinkAndALostNetworkAreRetried() {
        val expired = PlaybackException(
            "expired",
            invalidResponse(401),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        ).toPlaybackFailure()
        assertTrue(expired is PlaybackFailure.MediaCredentialUnavailable)
        assertTrue(expired.retryable)

        val offline = PlaybackException(
            "offline",
            IOException(),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        ).toPlaybackFailure()
        assertTrue(offline.putioFailure is PutioFailure.NetworkUnavailable)
        assertTrue(offline.retryable)
    }

    @Test
    fun aRejectedSessionOrRefusedFileIsNotRetried() {
        val cause = io.putdotio.sdk.errors.PutioConfigurationException("test")
        assertEquals(false, PlaybackFailure.Putio(PutioFailure.AuthenticationRequired(cause)).retryable)
        assertEquals(false, PlaybackFailure.Putio(PutioFailure.AccessDenied(cause)).retryable)
        assertEquals(true, PlaybackFailure.Putio(PutioFailure.RateLimited(cause)).retryable)
        assertEquals(true, PlaybackFailure.Putio(PutioFailure.ServerUnavailable(503, cause)).retryable)
        assertEquals(false, PlaybackFailure.Putio(PutioFailure.ApiRejected(400, null, cause)).retryable)
        assertEquals(true, PlaybackFailure.Putio(PutioFailure.ApiRejected(408, null, cause)).retryable)
    }

    @Test
    fun mediaRequestUnauthorizedOrForbiddenRefreshesThePlaybackCredentialButNotFoundDoesNot() {
        for (status in listOf(401, 403)) {
            assertTrue(
                "$status",
                IllegalStateException("player failed", invalidResponse(status)).toMediaRequestFailureOrNull() is
                    PlaybackFailure.MediaCredentialUnavailable,
            )
        }
        assertNull(IllegalStateException("player failed", invalidResponse(404)).toMediaRequestFailureOrNull())
    }

    @Test
    fun mediaRequestTransportFailureReportsNetworkUnavailable() {
        val transport =
            HttpDataSource.HttpDataSourceException(
                IOException("offline"),
                mediaDataSpec(),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )

        assertTrue(
            IllegalStateException("player failed", transport).toMediaRequestFailureOrNull()?.putioFailure is
                PutioFailure.NetworkUnavailable,
        )
    }

    @Test
    fun aNonNetworkDataSourceFailureUsesGenericRecovery() {
        val dataSourceFailure =
            HttpDataSource.HttpDataSourceException(
                IOException("cleartext rejected"),
                mediaDataSpec(),
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        val failure =
            PlaybackException("player failed", dataSourceFailure, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK)
                .toPlaybackFailure()

        assertTrue(failure.putioFailure is PutioFailure.Unexpected)
        assertTrue(failure.retryable)
    }

    @Test
    fun aStream403ResolvesAFreshLinkAtItsPositionAndOnlyASecond403IsShown() = runBlocking {
        val links = mutableListOf<String>()
        val repository = object : PlaybackRepository {
            override suspend fun resolve(
                target: PlaybackTarget,
                convertedOnly: Boolean,
            ): PlaybackRepositoryResult<PlaybackResolution> {
                val link = "https://api.put.io/v2/files/42/hls/media.m3u8?token=${links.size + 1}"
                links += link
                return PlaybackRepositoryResult.Success(
                    PlaybackResolution.Ready(hlsSource(link), useStartFrom = true),
                )
            }

            override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
        }
        val controller = PlaybackController(PlaybackTarget(FilesItemId(42L), "episode.mkv"), repository, this)
        try {
            val first = controller.awaitReady()
            val forbidden =
                PlaybackException("forbidden", invalidResponse(403), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

            assertTrue(controller.dispatch(PlaybackEvent.PlayerFailed(forbidden.toPlaybackFailure(), 30_000L)))
            val fresh = controller.awaitReady { it.source != first.source }
            assertEquals(links[1], fresh.source.url.value)
            assertEquals(30_000L, controller.state.value.resumePositionMillis)

            assertTrue(controller.dispatch(PlaybackEvent.PlayerFailed(forbidden.toPlaybackFailure(), 30_000L)))
            val shown = controller.state.value.content
            val shownFailure = (shown as? PlaybackContent.Failed)?.failure
            assertTrue("$shown", shownFailure is PlaybackFailure.MediaCredentialUnavailable)
            assertEquals(2, links.size)
        } finally {
            controller.close()
        }
    }

    private suspend fun PlaybackController.awaitReady(
        predicate: (PlaybackContent.Ready) -> Boolean = { true },
    ): PlaybackContent.Ready =
        withTimeout(2_000L) {
            state.first { (it.content as? PlaybackContent.Ready)?.let(predicate) == true }
                .content as PlaybackContent.Ready
        }

    private fun hlsSource(link: String) =
        PlaybackSource(
            fileId = 42L,
            kind = PlaybackSourceKind.HLS,
            url = PutioCredentialUrl.of(link),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.Embedded,
        )

    private fun invalidResponse(status: Int) =
        HttpDataSource.InvalidResponseCodeException(
            status, "HTTP $status", IOException("rejected"), emptyMap(), mediaDataSpec(), ByteArray(0),
        )

    private fun mediaDataSpec() = DataSpec(Uri.parse("https://example.invalid/media.m3u8"))
}
