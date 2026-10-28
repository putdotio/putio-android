package io.putdotio.android.telemetry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
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
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val bytes = Buffer().also { request.body?.writeTo(it) }.readByteArray()
            val gzip = request.header("Content-Encoding") == "gzip"
            val body = if (gzip) GZIPInputStream(bytes.inputStream()).readBytes() else bytes
            requests += request.url.encodedPath to body.decodeToString()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        }
        .build()
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

        // Each event goes in its own batch; whatever else the SDK sends would show up here too.
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
            "\$process_person_profile", "\$geoip_disable",
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

        sdk.stop()

        assertFalse(File(application.noBackupFilesDir, "io.put.analytics").exists())
        assertEquals(
            emptyList<String>(),
            application.dataDir.walk().map { it.name }.filter { "posthog" in it.lowercase() }.toList(),
        )
    }

    private companion object {
        const val TOKEN = "phc_SyntheticProjectToken0000000000"
        const val LEAK = "Big.Buck.Bunny.2008.mkv https://example.invalid/?oauth_token=secret"
    }
}
