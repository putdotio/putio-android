package io.putdotio.android.files

import io.putdotio.android.PutioResult
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class SdkFilesItemDetailsQueryTest {
    @Test
    fun resolvingAnItemAsksForItsResumePointAndNoSignedUrls() = runBlocking {
        val requests = mutableListOf<HttpUrl>()

        val result = SdkFilesRepository(client(requests)).resolveItem(FilesItemId(42L))

        val item = (result as PutioResult.Success).value
        assertEquals(12.5, item.playback?.startFromSeconds)
        val request = requests.single()
        assertEquals("/v2/files/42", request.encodedPath)
        assertEquals(mapOf("start_from" to "1"), request.queryParameterNames.associateWith(request::queryParameter))
    }

    private fun client(requests: MutableList<HttpUrl>): PutioClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests += chain.request().url
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("")
                        .body(FILE.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return PutioClient(PutioConfig(accessToken = "token"), http)
    }

    private companion object {
        const val FILE =
            """{"status":"OK","file":{"id":42,"name":"movie.mkv","parent_id":0,"size":1,""" +
                """"created_at":"2026-01-01T00:00:00","file_type":"VIDEO","start_from":12.5}}"""
    }
}
