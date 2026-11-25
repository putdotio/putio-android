package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkFilesWatchedRepositoryTest {
    @Test
    fun clearPositionsSendsBulkWatchStatusUnwatchedRequest() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        val repository = SdkFilesWatchedRepository(client(requests))

        val result = repository.clearPositions(listOf(FilesItemId(7L), FilesItemId(8L)))

        assertEquals(PutioResult.Success(Unit), result)
        val recorded = requests.single()
        assertEquals("POST", recorded.method)
        assertEquals("/v2/files/watch-status", recorded.path)
        assertEquals(mapOf("file_ids" to "7,8", "watched" to ""), recorded.formParams)
    }

    @Test
    fun clearPositionsShortCircuitsOnEmptyListWithoutHttpRequest() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        val repository = SdkFilesWatchedRepository(client(requests))

        val result = repository.clearPositions(emptyList())

        assertEquals(PutioResult.Success(Unit), result)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun clearPositionsRejectsNonPositiveIdsWithoutHttpRequest() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        val repository = SdkFilesWatchedRepository(client(requests))

        val result = repository.clearPositions(listOf(FilesItemId(0L))) as PutioResult.Failure

        assertTrue(result.failure.cause is IllegalArgumentException)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun clearPositionsDeduplicatesIds() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        val repository = SdkFilesWatchedRepository(client(requests))

        val result = repository.clearPositions(listOf(FilesItemId(7L), FilesItemId(8L), FilesItemId(7L)))

        assertEquals(PutioResult.Success(Unit), result)
        assertEquals("7,8", requests.single().formParams["file_ids"])
    }

    @Test
    fun clearPositionsClassifiesUnauthorizedAsAuthenticationRequired() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        val repository = SdkFilesWatchedRepository(client(requests, status = 401, body = UNAUTHORIZED))

        val result = repository.clearPositions(listOf(FilesItemId(7L), FilesItemId(8L))) as PutioResult.Failure

        assertTrue(result.failure is PutioFailure.AuthenticationRequired)
        assertEquals(1, requests.size)
    }

    private fun client(
        requests: MutableList<RecordedRequest>,
        status: Int = 200,
        body: String = """{"status":"OK"}""",
    ): PutioClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val bodyText = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    requests += RecordedRequest(
                        method = request.method,
                        path = request.url.encodedPath,
                        formParams = parseForm(bodyText),
                    )
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return PutioClient(PutioConfig(accessToken = "token"), http)
    }

    private fun parseForm(raw: String): Map<String, String> =
        raw.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val parts = pair.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val formParams: Map<String, String>,
    )

    private companion object {
        const val UNAUTHORIZED =
            """{"status":"ERROR","error_type":"invalid_grant","error_message":"Unauthorized","status_code":401}"""
    }
}
