package io.putdotio.android.tv

import io.putdotio.android.PutioFailure
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.PutioResult
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioTransportException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TvNativeConfigMigrationTest {
    @Test
    fun `a tv-native playback type is written once as the android key and read back`() = runBlocking {
        listOf("mp4" to VideoPlaybackType.Mp4, "hls" to VideoPlaybackType.Hls).forEach { (legacy, expected) ->
            val server = FakeConfigServer(TV_NATIVE_CONFIG.replace("\"mp4\"", "\"$legacy\""))

            assertEquals(expected, server.repository.loadPreferences().videoPlaybackType)
            assertEquals(expected, server.repository.loadPreferences().videoPlaybackType)

            assertEquals(listOf(AppConfigUpdate("video_playback_type", JsonPrimitive(legacy))), server.saves)
            assertEquals(JsonPrimitive("high"), server.config["bufferSize"])
        }
    }

    @Test
    fun `an existing android playback type is never overwritten`() = runBlocking {
        listOf("\"hls\"", "\"dash\"", "null").forEach { existing ->
            val server = FakeConfigServer(TV_NATIVE_CONFIG.replace("{", "{\"video_playback_type\": $existing,"))

            assertEquals(VideoPlaybackType.Hls, server.repository.loadPreferences().videoPlaybackType)

            assertEquals(emptyList<AppConfigUpdate>(), server.saves)
        }
    }

    @Test
    fun `a missing or unknown tv-native playback type writes nothing`() = runBlocking {
        listOf("""{}""", """{"playbackType": "dash"}""", """{"playbackType": 1}""", """{"playbackType": null}""")
            .forEach { blob ->
                val server = FakeConfigServer(blob)

                assertEquals(AndroidAppConfigPreferences(), server.repository.loadPreferences())

                assertEquals(emptyList<AppConfigUpdate>(), server.saves)
            }
    }

    @Test
    fun `a failed write still plays the mapped type and is retried on the next read`() = runBlocking {
        val server = FakeConfigServer(TV_NATIVE_CONFIG, failSaves = 1)

        assertEquals(VideoPlaybackType.Mp4, server.repository.loadPreferences().videoPlaybackType)
        assertEquals(VideoPlaybackType.Mp4, server.repository.loadPreferences().videoPlaybackType)
        assertEquals(VideoPlaybackType.Mp4, server.repository.loadPreferences().videoPlaybackType)

        assertEquals(2, server.saves.size)
        assertEquals(JsonPrimitive("mp4"), server.config["video_playback_type"])
    }

    @Test
    fun `a 401 on the write fails the read so the session expires`() = runBlocking {
        val rejected = PutioApiException(
            request = SAVE_REQUEST,
            resolvedStatusCode = 401,
            resolvedErrorType = "invalid_token",
            envelope = PutioApiErrorEnvelope(statusCode = 401, errorType = "invalid_token"),
            responseBody = "{}",
            message = "Request rejected",
        )
        val server = FakeConfigServer(TV_NATIVE_CONFIG, failSaves = 1, saveError = rejected)

        val result = server.repository.load() as PutioResult.Failure

        assertTrue(result.failure is PutioFailure.AuthenticationRequired)
    }

    /** `/config` for one user and OAuth app; a save lands like the server's `PUT /config/{key}`. */
    private class FakeConfigServer(
        blob: String,
        private var failSaves: Int = 0,
        private val saveError: PutioException = PutioTransportException(SAVE_REQUEST, IOException("offline")),
    ) {
        var config: Map<String, JsonElement> = Json.parseToJsonElement(blob).jsonObject
        val saves = mutableListOf<AppConfigUpdate>()
        private val save: suspend (AppConfigUpdate) -> Unit = { update ->
            saves += update
            if (failSaves > 0) {
                failSaves -= 1
                throw saveError
            }
            config = config + (update.key to update.value)
        }
        val repository = tvAppConfigRepository(getConfig = { AppConfig(config) }, saveConfig = save)
    }

    private companion object {
        val SAVE_REQUEST = PutioRequestData("PUT", "https://api.put.io/v2/config")

        /** What tv-native's `useConfigValue` leaves in `/config` after a viewer picks MP4 and a high buffer. */
        const val TV_NATIVE_CONFIG =
            """{"bufferSize": "high", "playbackType": "mp4", "searchHistory": ["dune"], "searchHistoryEnabled": true}"""

        suspend fun SdkAndroidAppConfigRepository.loadPreferences(): AndroidAppConfigPreferences =
            (load() as PutioResult.Success).value
    }
}
