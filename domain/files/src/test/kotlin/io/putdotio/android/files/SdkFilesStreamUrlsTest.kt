package io.putdotio.android.files

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkFilesStreamUrlsTest {
    @Test
    fun externalPlayerUrlsCarryTheDownloadTokenNeverTheAccessToken() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client(requests, downloadToken = "\"$DOWNLOAD_TOKEN\"")

        val result = SdkFilesStreamUrls(client).originalStreamUrl(FilesItemId(42L))

        val url = (result as FilesStreamUrlResult.Ready).url
        assertEquals("https://api.put.io/v2/files/42/stream?oauth_token=$DOWNLOAD_TOKEN", url)
        assertFalse(url.contains(ACCESS_TOKEN))
        assertFalse(result.toString().contains(DOWNLOAD_TOKEN))
        assertTrue(requests.single().endsWith("/account/info?download_token=1"))
    }

    @Test
    fun aMissingDownloadTokenFailsInsteadOfFallingBackToTheAccessToken() = runBlocking {
        val result = SdkFilesStreamUrls(client(mutableListOf(), downloadToken = null))
            .originalStreamUrl(FilesItemId(42L))

        assertEquals(FilesStreamUrlResult.DownloadTokenUnavailable, result)
    }

    @Test
    fun aRejectedSessionIsTheSessionVerdict() = runBlocking {
        val result = SdkFilesStreamUrls(client(mutableListOf(), downloadToken = null, status = 401))
            .originalStreamUrl(FilesItemId(42L))

        assertTrue((result as FilesStreamUrlResult.Failure).failure is FilesFailure.AuthenticationRequired)
    }

    private fun client(requests: MutableList<String>, downloadToken: String?, status: Int = 200): PutioClient {
        val body = if (status == 200) accountInfo(downloadToken) else UNAUTHORIZED
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
        return PutioClient(PutioConfig(accessToken = ACCESS_TOKEN), http)
    }

    private fun accountInfo(downloadToken: String?): String =
        """
        {"status":"OK","info":{"user_id":1,"username":"u","mail":"u@example.com","avatar_url":"",
        "disk":{"avail":1,"size":1,"used":0},"settings":{"sort_by":"NAME_ASC"},
        "account_status":"active","download_token":${downloadToken ?: "null"}}}
        """.trimIndent()

    private companion object {
        const val ACCESS_TOKEN = "full-oauth-access-token"
        const val DOWNLOAD_TOKEN = "narrow-download-token"
        const val UNAUTHORIZED =
            """{"status":"ERROR","error_type":"invalid_grant","error_message":"Unauthorized","status_code":401}"""
    }
}
