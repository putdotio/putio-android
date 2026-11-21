package io.putdotio.android.downloads

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MediaDownloadToken
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import io.putdotio.sdk.account.AccountDownloadToken
import io.putdotio.sdk.files.HLS_ALL_SUBTITLES
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileDownloadUpstreamTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val downloads = MobileDownloadCache.get(context)
    private val network = downloads.httpClient
    private val sent = CopyOnWriteArrayList<Request>()

    @After
    fun tearDown() {
        downloads.httpClient = network
        downloads.downloadToken = null
    }

    @Test
    fun mediaRequestsCarryTheAccountDownloadTokenNeverTheAccessToken() {
        val client = PutioClient(PutioConfig(accessToken = ACCESS_TOKEN), respondingWith(ACCOUNT))
        downloads.downloadToken = MediaDownloadToken.from(client)
        downloads.markSessionSettled()
        downloads.httpClient = respondingWith("media")

        read(API_URL)
        read(CDN_URL)

        val (api, cdn) = sent
        assertEquals("Token $DOWNLOAD_TOKEN", api.header("Authorization"))
        assertNull(api.url.queryParameter(OAUTH_TOKEN_QUERY))
        assertEquals(DOWNLOAD_TOKEN, cdn.url.queryParameter(OAUTH_TOKEN_QUERY))
        assertNull(cdn.header("Authorization"))
        assertTrue(sent.none { ACCESS_TOKEN in it.url.toString() || ACCESS_TOKEN in it.headers.toString() })
    }

    @Test
    fun downloadUrlsMatchTheSdkMediaRequestsByteForByte() {
        val files = PutioClient().files
        val token = AccountDownloadToken(DOWNLOAD_TOKEN)
        val id = FilesItemId(FILE_ID)

        assertEquals(files.buildOriginalStreamRequest(FILE_ID, token).url, DownloadArtifact.ORIGINAL.apiUrl(id))
        assertEquals(files.buildMp4StreamRequest(FILE_ID, token).url, DownloadArtifact.MP4.apiUrl(id))
        for (hidden in listOf(false, true)) {
            val subtitles = if (hidden) 0 else HLS_ALL_SUBTITLES
            assertEquals(
                files.buildHlsStreamRequest(FILE_ID, token, maxSubtitleCount = subtitles).url,
                DownloadArtifact.HLS.apiUrl(id, subtitlesHidden = hidden),
            )
            assertEquals(
                files.buildHlsStreamRequest(FILE_ID, token, maxSubtitleCount = subtitles, original = true).url,
                DownloadArtifact.ORIGINAL_HLS.apiUrl(id, subtitlesHidden = hidden),
            )
        }
    }

    private fun read(uri: String) {
        val source = downloads.upstreamFactory.createDataSource()
        DataSourceInputStream(source, DataSpec(uri.toUri())).use { it.readBytes() }
    }

    private fun respondingWith(body: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                if (!chain.request().url.encodedPath.endsWith("/account/info")) sent += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(HTTP_OK)
                    .message("OK")
                    .body(body.toResponseBody())
                    .build()
            },
        ).build()

    private companion object {
        const val HTTP_OK = 200
        const val FILE_ID = 9L
        const val ACCESS_TOKEN = "oauth-access-token"
        const val DOWNLOAD_TOKEN = "account-download-token"
        const val API_URL = "https://api.put.io/v2/files/9/stream"
        const val CDN_URL = "https://s1-cdn.put.io/stream/9?u=signed"
        const val ACCOUNT = """{
            "status":"OK","info":{
                "user_id":42,"username":"test-user","mail":"test@example.invalid",
                "avatar_url":"","account_status":"active","download_token":"$DOWNLOAD_TOKEN",
                "disk":{"avail":90,"size":100,"used":10},"settings":{"sort_by":"NAME_ASC","trash_enabled":true}
            }
        }"""
    }
}
