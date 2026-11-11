package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppConfigRepositoryTest {
    @Test
    fun parsesTypedValuesAndUsesAppOwnedDefaultsForMalformedInput() {
        val parsed =
            AppConfig(
                mapOf(
                    "streaming_method" to JsonPrimitive("file"),
                    AUTOPLAY_NEXT_VIDEO_KEY to JsonPrimitive(true),
                ),
            ).toAndroidPreferences()
        assertEquals(
            AndroidAppConfigPreferences(StreamingMethod.File, autoplayNextVideo = true),
            parsed,
        )

        listOf(
            AppConfig(emptyMap()),
            AppConfig(mapOf(STREAMING_METHOD_KEY to JsonPrimitive("dash"))),
            AppConfig(mapOf(STREAMING_METHOD_KEY to JsonPrimitive("mp4"))),
            AppConfig(mapOf(STREAMING_METHOD_KEY to JsonPrimitive(false))),
            AppConfig(mapOf(AUTOPLAY_NEXT_VIDEO_KEY to JsonPrimitive("true"))),
            AppConfig(mapOf(AUTOPLAY_NEXT_VIDEO_KEY to JsonArray(emptyList()))),
        ).forEach { malformed ->
            assertEquals(AndroidAppConfigPreferences(), malformed.toAndroidPreferences())
        }

        assertEquals(
            AndroidAppConfigPreferences(autoplayNextVideo = true),
            AppConfig(
                mapOf(
                    STREAMING_METHOD_KEY to JsonPrimitive("dash"),
                    AUTOPLAY_NEXT_VIDEO_KEY to JsonPrimitive(true),
                    "searchHistory" to JsonPrimitive("unrelated"),
                ),
            ).toAndroidPreferences(),
        )
        assertEquals(
            AndroidAppConfigPreferences(streamingMethod = StreamingMethod.File),
            AppConfig(
                mapOf(
                    STREAMING_METHOD_KEY to JsonPrimitive("file"),
                    AUTOPLAY_NEXT_VIDEO_KEY to JsonPrimitive("true"),
                ),
            ).toAndroidPreferences(),
        )
    }

    @Test
    fun rotateToLandscapeIsOnUnlessTheConfigSavedOff() {
        val rotates = { value: JsonPrimitive? ->
            AppConfig(listOfNotNull(value?.let { "auto_rotate_video" to it }).toMap())
                .toAndroidPreferences()
                .rotateToLandscape
        }
        assertTrue(rotates(null))
        assertTrue(rotates(JsonPrimitive("false")))
        assertEquals(false, rotates(JsonPrimitive(false)))
        assertEquals(
            AppConfigUpdate("auto_rotate_video", JsonPrimitive(false)),
            AndroidAppConfigChange.RotateToLandscape(enabled = false).toUpdate(),
        )
    }

    @Test
    fun ignoresLegacyPlaybackKeys() {
        assertEquals(
            AndroidAppConfigPreferences(streamingMethod = StreamingMethod.Hls),
            AppConfig(
                mapOf(
                    "video_playback_type" to JsonPrimitive("mp4"),
                    "play_originals" to JsonPrimitive(true),
                ),
            ).toAndroidPreferences(),
        )
    }

    @Test
    fun createsOneExactPerKeyUpdate() {
        assertEquals(
            AppConfigUpdate("streaming_method", JsonPrimitive("file")),
            AndroidAppConfigChange.PlaybackMethod(StreamingMethod.File).toUpdate(),
        )
        assertEquals(
            AppConfigUpdate("streaming_method", JsonPrimitive("hls")),
            AndroidAppConfigChange.PlaybackMethod(StreamingMethod.Hls).toUpdate(),
        )
        assertEquals(
            AppConfigUpdate(AUTOPLAY_NEXT_VIDEO_KEY, JsonPrimitive(true)),
            AndroidAppConfigChange.AutoplayNextVideo(enabled = true).toUpdate(),
        )
    }

    @Test
    fun adapterClassifiesSdkAndUnexpectedFailures() = runBlocking {
        val sdkFailure = PutioConfigurationException("missing client")
        val sdkRepository =
            SdkAndroidAppConfigRepository(
                getConfig = { throw sdkFailure },
                saveConfig = {},
            )
        val sdkResult = sdkRepository.load() as PutioResult.Failure
        assertTrue(sdkResult.failure is PutioFailure.Misconfigured)

        val unexpected = IllegalStateException("broken")
        val unexpectedRepository =
            SdkAndroidAppConfigRepository(
                getConfig = { AppConfig(emptyMap()) },
                saveConfig = { throw unexpected },
            )
        val unexpectedResult =
            unexpectedRepository.save(AndroidAppConfigChange.PlaybackMethod(StreamingMethod.Hls))
                as PutioResult.Failure
        assertEquals(PutioFailure.Unexpected(unexpected), unexpectedResult.failure)
    }

    @Test
    fun saveAndAuthoritativeRefreshRemainSeparateEffects() = runBlocking {
        val operations = mutableListOf<String>()
        val updates = mutableListOf<AppConfigUpdate>()
        val repository =
            SdkAndroidAppConfigRepository(
                getConfig = {
                    operations += "read"
                    AppConfig(mapOf(STREAMING_METHOD_KEY to JsonPrimitive("hls")))
                },
                saveConfig = { update ->
                    operations += "save"
                    updates += update
                },
            )
        val requestId = AndroidAppConfigRequestId(7L)
        val change = AndroidAppConfigChange.PlaybackMethod(StreamingMethod.File)

        assertEquals(
            AndroidAppConfigEvent.SaveSucceeded(requestId),
            repository.execute(AndroidAppConfigEffect.Save(requestId, change)),
        )
        assertEquals(listOf("save"), operations)
        assertEquals(listOf(change.toUpdate()), updates)
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

        val authResult = failingRepository(authentication).load() as PutioResult.Failure
        assertTrue(authResult.failure is PutioFailure.AuthenticationRequired)
        assertSame(authentication, authResult.failure.cause)

        val deniedResult = failingRepository(denied).load() as PutioResult.Failure
        assertTrue(deniedResult.failure is PutioFailure.AccessDenied)
        assertSame(denied, deniedResult.failure.cause)
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
