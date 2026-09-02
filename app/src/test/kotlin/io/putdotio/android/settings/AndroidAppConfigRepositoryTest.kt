package io.putdotio.android.settings

import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidAppConfigRepositoryTest {
    @Test
    fun parsesTypedValuesAndUsesAppOwnedDefaultsForMalformedInput() {
        val parsed =
            AppConfig(
                mapOf(
                    VIDEO_PLAYBACK_TYPE_KEY to JsonPrimitive("mp4"),
                    AUTOPLAY_NEXT_VIDEO_KEY to JsonPrimitive(true),
                ),
            ).toAndroidPreferences()
        assertEquals(AndroidAppConfigPreferences(VideoPlaybackType.Mp4, autoplayNextVideo = true), parsed)

        listOf(
            AppConfig(emptyMap()),
            AppConfig(mapOf(VIDEO_PLAYBACK_TYPE_KEY to JsonPrimitive("dash"))),
            AppConfig(mapOf(VIDEO_PLAYBACK_TYPE_KEY to JsonPrimitive(false))),
            AppConfig(mapOf(AUTOPLAY_NEXT_VIDEO_KEY to JsonPrimitive("true"))),
            AppConfig(mapOf(AUTOPLAY_NEXT_VIDEO_KEY to JsonArray(emptyList()))),
        ).forEach { malformed ->
            assertEquals(AndroidAppConfigPreferences(), malformed.toAndroidPreferences())
        }

        assertEquals(
            AndroidAppConfigPreferences(autoplayNextVideo = true),
            AppConfig(
                mapOf(
                    VIDEO_PLAYBACK_TYPE_KEY to JsonPrimitive("dash"),
                    AUTOPLAY_NEXT_VIDEO_KEY to JsonPrimitive(true),
                    "searchHistory" to JsonPrimitive("unrelated"),
                ),
            ).toAndroidPreferences(),
        )
        assertEquals(
            AndroidAppConfigPreferences(videoPlaybackType = VideoPlaybackType.Mp4),
            AppConfig(
                mapOf(
                    VIDEO_PLAYBACK_TYPE_KEY to JsonPrimitive("mp4"),
                    AUTOPLAY_NEXT_VIDEO_KEY to JsonPrimitive("true"),
                ),
            ).toAndroidPreferences(),
        )
    }

    @Test
    fun createsOneExactPerKeyUpdate() {
        assertEquals(
            AppConfigUpdate(VIDEO_PLAYBACK_TYPE_KEY, JsonPrimitive("mp4")),
            AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4).toUpdate(),
        )
        assertEquals(
            AppConfigUpdate(AUTOPLAY_NEXT_VIDEO_KEY, JsonPrimitive(true)),
            AndroidAppConfigChange.AutoplayNextVideo(enabled = true).toUpdate(),
        )
    }

    @Test
    fun adapterLoadsAndSavesThroughGenericSdkBoundary() = runBlocking {
        val updates = mutableListOf<AppConfigUpdate>()
        val repository =
            SdkAndroidAppConfigRepository(
                getConfig = { AppConfig(mapOf(VIDEO_PLAYBACK_TYPE_KEY to JsonPrimitive("mp4"))) },
                saveConfig = updates::add,
            )

        val loaded = repository.load() as AndroidAppConfigRepositoryResult.Success
        assertEquals(VideoPlaybackType.Mp4, loaded.value.videoPlaybackType)

        val change = AndroidAppConfigChange.AutoplayNextVideo(enabled = true)
        assertTrue(repository.save(change) is AndroidAppConfigRepositoryResult.Success)
        assertEquals(listOf(change.toUpdate()), updates)
    }

    @Test
    fun adapterClassifiesSdkAndUnexpectedFailures() = runBlocking {
        val sdkFailure = PutioConfigurationException("missing client")
        val sdkRepository =
            SdkAndroidAppConfigRepository(
                getConfig = { throw sdkFailure },
                saveConfig = {},
            )
        val sdkResult = sdkRepository.load() as AndroidAppConfigRepositoryResult.Failure
        assertTrue(sdkResult.failure is AndroidAppConfigFailure.Misconfigured)

        val unexpected = IllegalStateException("broken")
        val unexpectedRepository =
            SdkAndroidAppConfigRepository(
                getConfig = { AppConfig(emptyMap()) },
                saveConfig = { throw unexpected },
            )
        val unexpectedResult =
            unexpectedRepository.save(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Hls))
                as AndroidAppConfigRepositoryResult.Failure
        assertEquals(AndroidAppConfigFailure.Unexpected(unexpected), unexpectedResult.failure)
    }

    @Test
    fun saveAndAuthoritativeRefreshRemainSeparateEffects() = runBlocking {
        val operations = mutableListOf<String>()
        val repository =
            SdkAndroidAppConfigRepository(
                getConfig = {
                    operations += "read"
                    AppConfig(mapOf(VIDEO_PLAYBACK_TYPE_KEY to JsonPrimitive("hls")))
                },
                saveConfig = { operations += "save" },
            )
        val requestId = AndroidAppConfigRequestId(7L)
        val change = AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)

        assertEquals(
            AndroidAppConfigEvent.SaveSucceeded(requestId),
            repository.execute(AndroidAppConfigEffect.Save(requestId, change)),
        )
        assertEquals(listOf("save"), operations)
        assertEquals(
            AndroidAppConfigEvent.RefreshSucceeded(requestId, AndroidAppConfigPreferences()),
            repository.execute(AndroidAppConfigEffect.Refresh(requestId)),
        )
        assertEquals(listOf("save", "read"), operations)
    }

    @Test
    fun classifiesAuthenticationAndKeepsInvalidScopeAsAccessDenied() = runBlocking {
        val authentication = operationError(statusCode = 401, errorType = "invalid_token")
        val denied = operationError(statusCode = 401, errorType = "invalid_scope")

        val authResult = failingRepository(authentication).load() as AndroidAppConfigRepositoryResult.Failure
        assertTrue(authResult.failure is AndroidAppConfigFailure.AuthenticationRequired)
        assertSame(authentication, authResult.failure.cause)

        val deniedResult = failingRepository(denied).load() as AndroidAppConfigRepositoryResult.Failure
        assertTrue(deniedResult.failure is AndroidAppConfigFailure.AccessDenied)
        assertSame(denied, deniedResult.failure.cause)
    }

    @Test
    fun preservesCancellation() {
        val cancellation = CancellationException("screen closed")
        try {
            runBlocking { failingRepository(cancellation).load() }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun failingRepository(error: Throwable) =
        SdkAndroidAppConfigRepository(
            getConfig = { throw error },
            saveConfig = {},
        )

    private fun operationError(
        statusCode: Int,
        errorType: String,
    ): PutioOperationException {
        val apiError =
            PutioApiException(
                request = PutioRequestData("GET", "https://api.put.io/v2/config"),
                resolvedStatusCode = statusCode,
                resolvedErrorType = errorType,
                envelope = PutioApiErrorEnvelope(statusCode = statusCode, errorType = errorType),
                responseBody = "{}",
                message = "Request rejected",
            )
        return PutioOperationException(
            domain = "config",
            operation = "get",
            contract = null,
            reason = PutioOperationErrorReason.StatusCode(statusCode),
            underlyingError = apiError,
        )
    }
}
