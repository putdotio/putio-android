package io.putdotio.android.playback

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import java.io.IOException
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

    private fun invalidResponse(status: Int) =
        HttpDataSource.InvalidResponseCodeException(
            status, "HTTP $status", IOException("rejected"), emptyMap(), mediaDataSpec(), ByteArray(0),
        )

    private fun mediaDataSpec() = DataSpec(Uri.parse("https://example.invalid/media.m3u8"))
}
