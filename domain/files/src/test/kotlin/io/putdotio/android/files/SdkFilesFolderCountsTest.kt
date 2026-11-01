package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkFilesFolderCountsTest {
    @Test
    fun readsTheTotalFromOneRow() = runBlocking {
        val requests = mutableListOf<String>()
        val body = """{"status":"OK","files":[],"parent":null,"cursor":null,"total":3}"""

        val result = SdkFilesFolderCounts(client(requests, 200, body)).itemCount(FilesItemId(10L))

        assertEquals(3, (result as PutioResult.Success).value)
        val url = requests.single()
        assertTrue(url, "parent_id=10" in url && "per_page=1" in url && "total=1" in url)
    }

    @Test
    fun countsOnlyTheFoldersInside() = runBlocking {
        val requests = mutableListOf<String>()
        val body = """{"status":"OK","files":[],"parent":null,"cursor":null,"total":12}"""

        val result = SdkFilesFolderCounts(client(requests, 200, body)).folderCount(FilesItemId(10L))

        assertEquals(12, (result as PutioResult.Success).value)
        val url = requests.single()
        assertTrue(url, "parent_id=10" in url && "file_type=FOLDER" in url && "total=1" in url)
    }

    @Test
    fun aRejectedSessionIsTheSessionVerdict() = runBlocking {
        val result = SdkFilesFolderCounts(client(mutableListOf(), 401, UNAUTHORIZED)).itemCount(FilesItemId(10L))

        assertTrue((result as PutioResult.Failure).failure is PutioFailure.AuthenticationRequired)
    }

    private fun client(requests: MutableList<String>, status: Int, body: String): PutioClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests += chain.request().url.toString()
                    Response.Builder()
                        .request(chain.request())
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

    private companion object {
        const val UNAUTHORIZED =
            """{"status":"ERROR","error_type":"invalid_grant","error_message":"Unauthorized","status_code":401}"""
    }
}
