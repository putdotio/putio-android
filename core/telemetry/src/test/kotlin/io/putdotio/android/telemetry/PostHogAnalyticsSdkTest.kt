package io.putdotio.android.telemetry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Drives the real PostHog SDK and reads what it puts on the wire; only the network is faked. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PostHogAnalyticsSdkTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val requests = LinkedBlockingQueue<Pair<String, String>>()
    private val client = recordingClient()
    private val configuration = checkNotNull(
        ProductAnalyticsConfiguration.of(TOKEN, tv = false, "production", "3.0.1", 3_000_100),
    )

    @Test
    fun onlyContractFieldsLeaveUnderTheAccountId() {
        val sdk = PostHogAnalyticsSdk(application, configuration, client)
        sdk.stop()
        sdk.start(42)

        sdk.capture("screen_viewed", mapOf("screen" to "files", "file_name" to LEAK, "route" to LEAK), 1_000L)
        sdk.capture("search_performed", mapOf("query" to LEAK), 1_000L)
        sdk.capture("app_opened", mapOf("entry" to LEAK), 2_000L)

        val sent = generateSequence { requests.poll(5, TimeUnit.SECONDS) }.take(2).toList() +
            generateSequence { requests.poll(1, TimeUnit.SECONDS) }.toList()
        assertEquals(listOf("/batch", "/batch"), sent.map { it.first.trimEnd('/') })
        sent.forEach { (_, body) -> assertFalse("a leak reached the wire: $body", body.contains(LEAK)) }
        val events = sent.flatMap { (_, body) ->
            val batch = JSONObject(body).getJSONArray("batch")
            (0 until batch.length()).map(batch::getJSONObject)
        }
        assertEquals(listOf("screen_viewed", "app_opened"), events.map { it.getString("event") })
        val screen = events[0]
        assertEquals("42", screen.getString("distinct_id"))
        val properties = screen.getJSONObject("properties")
        val keys = properties.keys().asSequence().toSet()
        val listed = setOf(
            "screen", "app", "runtime", "runtime_version", "device_class", "auth_state", "environment",
            "release_channel", "app_version", "\$lib", "\$lib_version", "\$session_id", "\$is_identified",
            "\$geoip_disable",
        )
        assertEquals(emptySet<String>(), keys - listed)
        assertEquals("files", properties.getString("screen"))
        assertEquals("android", properties.getString("app"))
        assertEquals("3.0.1+3000100", properties.getString("app_version"))
        assertEquals("phone", properties.getString("device_class"))
        assertTrue(properties.getBoolean("\$geoip_disable"))
        assertFalse("app_opened kept an unlisted value", events[1].getJSONObject("properties").has("entry"))
    }

    @Test
    fun stoppingLeavesNothingOnTheDevice() {
        val sdk = PostHogAnalyticsSdk(application, configuration, client)
        sdk.start(42)
        sdk.capture("screen_viewed", mapOf("screen" to "files"), 1_000L)
        assertEquals("/batch", requests.poll(5, TimeUnit.SECONDS)?.first?.trimEnd('/'))
        assertEquals(emptyList<String>(), analyticsFiles())

        sdk.stop()

        assertEquals(emptyList<String>(), analyticsFiles())
    }

    @Test
    fun eventsQueuedBeforeOptOutAreNeverSent() {
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sdk = PostHogAnalyticsSdk(
            application,
            configuration,
            recordingClient {
                sending.countDown()
                release.await(5, TimeUnit.SECONDS)
                OK
            },
        )
        sdk.start(42)
        sdk.capture("screen_viewed", mapOf("screen" to "files"), 1_000L)
        assertTrue(sending.await(5, TimeUnit.SECONDS))
        sdk.capture("app_opened", emptyMap(), 2_000L)

        sdk.stop()
        release.countDown()

        val sent = generateSequence { requests.poll(2, TimeUnit.SECONDS) }.toList()
        assertEquals(1, sent.size)
        assertTrue(sent.single().second.contains("screen_viewed"))
    }

    @Test
    fun aBatchTheProxyRejectsIsDroppedAndLaterEventsStillGo() {
        var calls = 0
        val sdk = PostHogAnalyticsSdk(
            application,
            configuration,
            recordingClient { if (calls++ == 0) BAD_REQUEST else OK },
        )
        sdk.start(42)
        sdk.capture("screen_viewed", mapOf("screen" to "files"), 1_000L)
        assertTrue(requests.poll(5, TimeUnit.SECONDS)?.second.orEmpty().contains("screen_viewed"))

        sdk.capture("app_opened", emptyMap(), 2_000L)

        val retried = requests.poll(5, TimeUnit.SECONDS)?.second.orEmpty()
        assertTrue(retried.contains("app_opened"))
        assertFalse(retried.contains("screen_viewed"))
        sdk.stop()
    }

    private fun analyticsFiles(): List<String> = (application.dataDir.walk() + application.noBackupFilesDir.walk())
        .filter { it.name == "io.put.analytics" || "posthog" in it.name.lowercase() }
        .map { it.path }
        .distinct()
        .toList()

    private fun recordingClient(status: () -> Int = { OK }) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val bytes = Buffer().also { request.body?.writeTo(it) }.readByteArray()
            val gzip = request.header("Content-Encoding") == "gzip"
            val body = if (gzip) GZIPInputStream(bytes.inputStream()).readBytes() else bytes
            requests += request.url.encodedPath to body.decodeToString()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status())
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        }
        .build()

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val TOKEN = "phc_SyntheticProjectToken0000000000"
        const val LEAK = "Big.Buck.Bunny.2008.mkv https://example.invalid/?oauth_token=secret"
    }
}
