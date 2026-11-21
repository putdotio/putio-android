package io.putdotio.android.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LoggedInAppIconRequestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = CopyOnWriteArrayList<Request>()

    @Test
    fun anIconIsFetchedFromItsTokenFreeUrlWithTheAccessTokenInTheHeader() = runBlocking {
        val icon = PutioClient(PutioConfig(accessToken = ACCESS_TOKEN)).grants.buildIconRequest(APP_ID)
        val app = loggedInApp(APP_ID, "put.io for iOS").copy(icon = icon)
        val request = checkNotNull(loggedInAppIconRequest(context, app))

        loader().execute(request)

        val fetched = sent.single()
        assertEquals(ICON_URL, fetched.url.toString())
        assertNull(fetched.url.queryParameter("oauth_token"))
        assertEquals("Token $ACCESS_TOKEN", fetched.header("Authorization"))
        assertEquals(ICON_URL, request.diskCacheKey)
    }

    private fun loader(): ImageLoader {
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    sent += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(HTTP_NOT_FOUND)
                        .message("Not Found")
                        .body("".toResponseBody())
                        .build()
                },
            )
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .diskCache(null)
            .build()
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val APP_ID = 2L
        const val ACCESS_TOKEN = "oauth-access-token"
        const val ICON_URL = "https://api.put.io/v2/oauth/apps/2/icon"
    }
}
