package io.putdotio.android

import io.putdotio.android.files.apiReason
import io.putdotio.android.files.toFilesFailure
import io.putdotio.android.playback.apiReason
import io.putdotio.android.playback.toPlaybackFailure
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AndroidAppConfigRepositoryResult
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.apiReason
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioTransportException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiRejectionReasonTest {
    @Test
    fun `every surface's failure carries put io's message for a refused request`() = runBlocking {
        val refusal = refusal(400, "Folder is full, empty some space first.")

        assertEquals(REASON, refusal.toFilesFailure().apiReason)
        assertEquals(REASON, refusal.toPlaybackFailure().apiReason)
        val settings = SdkAccountSettingsRepository(getSettings = { throw refusal }, saveSettings = {}).load()
        assertEquals(REASON, (settings as AccountSettingsRepositoryResult.Failure).failure.apiReason)
        val config = SdkAndroidAppConfigRepository(getConfig = { throw refusal }, saveConfig = {}).load()
        assertEquals(REASON, (config as AndroidAppConfigRepositoryResult.Failure).failure.apiReason)
    }

    @Test
    fun `the reason is read through the SDK's operation wrapper and normalised`() {
        val wrapped = PutioOperationException(
            domain = "files",
            operation = "rename",
            contract = null,
            reason = null,
            underlyingError = refusal(409, "  A file with this name\n already exists.  "),
        )

        assertEquals("A file with this name already exists.", wrapped.apiRejectionReason())
        assertEquals("not a folder", refusal(404, "not a folder").apiRejectionReason())
    }

    @Test
    fun `statuses the app explains itself, server errors and transport failures keep the app's copy`() {
        listOf(401, 403, 408, 429, 500, 503).forEach { status ->
            assertNull("$status", refusal(status, REASON).apiRejectionReason())
            assertNull("$status", refusal(status, REASON).toFilesFailure().apiReason)
        }
        assertNull(refusal(400, REASON, httpStatusCode = 502).apiRejectionReason())
        val transport =
            PutioTransportException(PutioRequestData("GET", "https://api.put.io/v2/files/list"), IOException("reset"))
        assertNull(transport.toFilesFailure().apiReason)
    }

    @Test
    fun `codes, URLs, redacted secrets and missing messages are not shown`() {
        listOf(
            "FILE_NOT_FOUND",
            "The requested URL was not found on the server. If you entered the URL manually please check your " +
                "spelling and try again.",
            "See https://example.invalid/help for details",
            "Invalid value for key: 'next'?access_token=secret-value",
            "   ",
            "x".repeat(301),
        ).forEach { message -> assertNull(message, refusal(400, message).apiRejectionReason()) }

        assertNull(putioRefusal(400, "<html>Bad Request</html>").apiRejectionReason())
        assertNull(putioRefusal(400, """{"status":"ERROR","status_code":400}""").apiRejectionReason())
    }

    private fun refusal(status: Int, message: String) = putioRefusal(status, putioErrorBody(status, message))

    private fun refusal(status: Int, message: String, httpStatusCode: Int) =
        putioRefusal(status, putioErrorBody(status, message), httpStatusCode)

    private companion object {
        const val REASON = "Folder is full, empty some space first."
    }
}

/**
 * A put.io error response as the SDK decodes it: the envelope has no `message`, because put.io
 * sends `error_message`.
 */
internal fun putioRefusal(status: Int, body: String, httpStatusCode: Int = status): PutioApiException {
    val envelope = runCatching { lenientJson.decodeFromString(PutioApiErrorEnvelope.serializer(), body) }
        .getOrElse { PutioApiErrorEnvelope(statusCode = status) }
    assertNull(envelope.message)
    return PutioApiException(
        request = PutioRequestData("POST", "https://api.put.io/v2/files/rename"),
        resolvedStatusCode = envelope.statusCode ?: status,
        httpStatusCode = httpStatusCode,
        resolvedErrorType = envelope.errorType,
        envelope = envelope,
        responseBody = body,
        message = "put.io returned HTTP $status",
    )
}

/** The body put.io's API sends for a refused request. */
internal fun putioErrorBody(status: Int, message: String): String =
    buildJsonObject {
        put("error_id", null as String?)
        put("error_message", message)
        put("error_type", "BadRequest")
        put("error_uri", "http://api.put.io/v2/docs")
        put("status", "ERROR")
        put("status_code", status)
    }.toString()

private val lenientJson = Json { ignoreUnknownKeys = true }
