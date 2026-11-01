package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioTransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppConfigPinnedFilesTest {
    private val server = FakeConfig(
        mutableMapOf(
            PINNED_FILES_KEY to ids(7L, 9L),
            "playbackType" to JsonPrimitive("hls"),
        ),
    )

    @Test
    fun pinningWritesOnlyItsKeyNewestFirstAndKeepsAPinAnotherTvMade() = runTest {
        val pins = store()
        advanceUntilIdle()
        assertEquals(listOf(7L, 9L), pins.pins.value?.map { it.value })

        server.values[PINNED_FILES_KEY] = ids(5L, 7L, 9L)
        pins.pin(FilesItemId(9L))
        advanceUntilIdle()

        assertEquals(listOf(9L, 5L, 7L), pins.pins.value?.map { it.value })
        assertEquals(listOf(AppConfigUpdate(PINNED_FILES_KEY, ids(9L, 5L, 7L))), server.writes)
        assertEquals(JsonPrimitive("hls"), server.values["playbackType"])
        pins.close()
    }

    @Test
    fun unpinningRemovesTheIdAndARefusedWriteRollsBack() = runTest {
        val pins = store()
        pins.unpin(FilesItemId(7L))
        advanceUntilIdle()
        assertEquals(listOf(9L), pins.pins.value?.map { it.value })

        server.refuseWrites = true
        pins.pin(FilesItemId(3L))
        advanceUntilIdle()

        assertEquals(listOf(9L), pins.pins.value?.map { it.value })
        assertTrue(pins.failure.value is PutioFailure.NetworkUnavailable)
        assertTrue(pins.editFailure.value is PutioFailure.NetworkUnavailable)
        pins.dismissEditFailure()
        assertNull(pins.editFailure.value)
        pins.close()
    }

    @Test
    fun aRefusedWriteForAGoneFileIsNoFailedEdit() = runTest {
        val pins = store()
        advanceUntilIdle()
        server.refuseWrites = true
        pins.unpinGone(FilesItemId(7L))
        advanceUntilIdle()

        assertEquals(listOf(7L, 9L), pins.pins.value?.map { it.value })
        assertTrue(pins.failure.value is PutioFailure.NetworkUnavailable)
        assertNull(pins.editFailure.value)
        pins.close()
    }

    @Test
    fun aFailedReadWaitsForReloadAndInvalidScopeIsNoSessionVerdict() = runTest {
        server.readFailure = apiException(401, "invalid_scope")
        val pins = store()
        advanceUntilIdle()
        assertNull(pins.pins.value)
        assertTrue(pins.failure.value is PutioFailure.AccessDenied)
        assertNull(pins.editFailure.value)

        server.readFailure = null
        pins.reload()
        advanceUntilIdle()

        assertEquals(listOf(7L, 9L), pins.pins.value?.map { it.value })
        assertNull(pins.failure.value)
        pins.close()
    }

    @Test
    fun reloadingPicksUpPinsAnotherTvChanged() = runTest {
        val pins = store()
        advanceUntilIdle()

        server.values[PINNED_FILES_KEY] = ids(5L, 7L)
        pins.reload()
        advanceUntilIdle()

        assertEquals(listOf(5L, 7L), pins.pins.value?.map { it.value })
        pins.close()
    }

    @Test
    fun readsPositiveNumericIdsOnlyAndCapsTheRow() {
        val config = AppConfig(
            mapOf(
                PINNED_FILES_KEY to JsonArray(
                    listOf(
                        JsonPrimitive(4L),
                        JsonPrimitive("5"),
                        JsonPrimitive(0L),
                        JsonObject(emptyMap()),
                        JsonPrimitive(4L),
                        JsonPrimitive(6L),
                    ),
                ),
            ),
        )
        assertEquals(listOf(4L, 6L), config.pinnedFiles().map { it.value })
        assertTrue(AppConfig(mapOf(PINNED_FILES_KEY to JsonPrimitive("x"))).pinnedFiles().isEmpty())

        val many = AppConfig(mapOf(PINNED_FILES_KEY to ids(*(1L..60L).toList().toLongArray())))
        assertEquals(MAX_PINNED_FILES, many.pinnedFiles().size)
    }

    private fun TestScope.store(): AppConfigPinnedFiles =
        AppConfigPinnedFiles(
            loadConfig = server::load,
            saveConfig = server::save,
            parentScope = CoroutineScope(coroutineContext + StandardTestDispatcher(testScheduler)),
        )

    private class FakeConfig(val values: MutableMap<String, JsonElement>) {
        val writes = mutableListOf<AppConfigUpdate>()
        var refuseWrites = false
        var readFailure: Exception? = null

        fun load(): AppConfig {
            readFailure?.let { throw it }
            return AppConfig(values.toMap())
        }

        fun save(update: AppConfigUpdate) {
            if (refuseWrites) {
                throw PutioTransportException(PutioRequestData("PUT", CONFIG_URL), java.io.IOException("offline"))
            }
            writes += update
            values[update.key] = update.value
        }
    }

    private companion object {
        const val CONFIG_URL = "https://api.put.io/v2/config/pinned_files"

        fun ids(vararg values: Long) = JsonArray(values.map { JsonPrimitive(it) })

        fun apiException(status: Int, errorType: String) = PutioApiException(
            request = PutioRequestData("GET", "https://api.put.io/v2/config"),
            resolvedStatusCode = status,
            httpStatusCode = status,
            resolvedErrorType = errorType,
            envelope = PutioApiErrorEnvelope(statusCode = status, errorType = errorType),
            responseBody = "{}",
            message = "Rejected",
        )
    }
}
