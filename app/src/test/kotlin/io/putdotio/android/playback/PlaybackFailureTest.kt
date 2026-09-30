package io.putdotio.android.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(failure is PlaybackFailure.Unexpected)
        assertTrue(failure.retryable)
    }

    @Test
    fun anExpiredMediaLinkAndALostNetworkAreRetried() {
        val expired = PlaybackException(
            "expired",
            HttpDataSource.InvalidResponseCodeException(
                401,
                "Unauthorized",
                null,
                emptyMap(),
                DataSpec(android.net.Uri.parse("https://example.invalid/media.m3u8")),
                ByteArray(0),
            ),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        ).toPlaybackFailure()
        assertTrue(expired is PlaybackFailure.MediaCredentialUnavailable)
        assertTrue(expired.retryable)

        val offline = PlaybackException(
            "offline",
            IOException(),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        ).toPlaybackFailure()
        assertTrue(offline is PlaybackFailure.NetworkUnavailable)
        assertTrue(offline.retryable)
    }

    @Test
    fun aRejectedSessionOrRefusedFileIsNotRetried() {
        val cause = io.putdotio.sdk.errors.PutioConfigurationException("test")
        assertEquals(false, PlaybackFailure.AuthenticationRequired(cause).retryable)
        assertEquals(false, PlaybackFailure.AccessDenied(cause).retryable)
        assertEquals(true, PlaybackFailure.RateLimited(cause).retryable)
        assertEquals(true, PlaybackFailure.ServerUnavailable(503, cause).retryable)
    }
}
