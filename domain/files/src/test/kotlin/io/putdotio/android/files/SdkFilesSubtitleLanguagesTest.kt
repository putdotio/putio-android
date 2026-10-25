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

class SdkFilesSubtitleLanguagesTest {
    @Test
    fun eachLanguageShowsOnceInPutiosOrderAndANamelessLanguageFallsBackToTheName() = runBlocking {
        val requests = mutableListOf<String>()
        val body = """
            {"status":"OK","default":"a","subtitles":[
              ${subtitle("a", "English", "Blue.Lights.S04E01.en.srt")},
              ${subtitle("b", "English", "Blue.Lights.S04E01.en.sdh.srt")},
              ${subtitle("c", "", "Blue.Lights.S04E01.srt")},
              ${subtitle("d", "Turkish", "Blue.Lights.S04E01.tr.srt")}
            ]}
        """.trimIndent()

        val result = SdkFilesSubtitleLanguages(client(requests, 200, body)).subtitleLanguages(FilesItemId(42L))

        assertEquals(
            listOf("English", "Blue.Lights.S04E01.srt", "Turkish"),
            (result as PutioResult.Success).value,
        )
        assertTrue(requests.single().endsWith("/files/42/subtitles"))
    }

    @Test
    fun aRejectedSessionIsTheSessionVerdict() = runBlocking {
        val result = SdkFilesSubtitleLanguages(client(mutableListOf(), 401, UNAUTHORIZED))
            .subtitleLanguages(FilesItemId(42L))

        assertTrue((result as PutioResult.Failure).failure is PutioFailure.AuthenticationRequired)
    }

    private fun subtitle(key: String, language: String, name: String): String =
        """{"key":"$key","language":"$language","language_code":"","name":"$name","source":"folder","url":""}"""

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
