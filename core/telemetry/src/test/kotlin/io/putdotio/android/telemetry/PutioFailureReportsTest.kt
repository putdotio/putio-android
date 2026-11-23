package io.putdotio.android.telemetry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import io.putdotio.android.PutioFailure
import io.putdotio.sdk.errors.PutioConfigurationException
import io.sentry.IConnectionStatusProvider
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PutioFailureReportsTest {
    private val received = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val root = Files.createTempDirectory("failure-reports").toFile()
    private val files = DiagnosticsFiles(File(root, "cache"), File(root, "INSTALLATION"), File(root, "id"))

    @Before
    fun listen() {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.let {
                if (exchange.requestHeaders.getFirst("Content-Encoding") == "gzip") GZIPInputStream(it) else it
            }
            received += body.readBytes().decodeToString()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        val period = SentryBoundary.open(launch = true)
        SentryAndroid.init(ApplicationProvider.getApplicationContext<Application>()) { options ->
            SentryBoundary.configure(options, checkNotNull(CONFIGURATION), period, files)
            options.cacheDirPath = null
            options.dsn = "http://synthetickey@127.0.0.1:${server.address.port}/1"
            options.connectionStatusProvider = Connected
        }
        SentryBoundary.confirm()
    }

    @After
    fun close() {
        Sentry.close()
        SentryBoundary.stop(files).get()
        server.stop(0)
        root.deleteRecursively()
    }

    @Test
    fun unexpectedFailureCapturesOnceAndReusesEventId() {
        val failure = PutioFailure.Unexpected(IllegalStateException("unexpected boom"))
        val firstId = checkNotNull(PutioFailureReports.idFor(failure))
        awaitEnvelope("\"event_id\":\"$firstId\"")

        val secondId = PutioFailureReports.idFor(failure)
        Sentry.flush(FLUSH_MILLIS)

        assertEquals(firstId, secondId)
        assertEquals(firstId, PutioFailureReports.reported(failure))
        val matches = received.filter { "\"event_id\":\"$firstId\"" in it }
        assertEquals("envelopes=$matches", 1, matches.size)
        assertTrue(matches.single().contains("putio_failure"))
        assertTrue(matches.single().contains("unexpected"))
    }

    @Test
    fun invalidResponseCapturesWithInvalidResponseKind() {
        val failure = PutioFailure.InvalidResponse(PutioConfigurationException("schema drift"))
        val id = checkNotNull(PutioFailureReports.idFor(failure))

        awaitEnvelope("\"event_id\":\"$id\"")

        val envelope = received.single { "\"event_id\":\"$id\"" in it }
        assertTrue(envelope.contains("putio_failure"))
        assertTrue(envelope.contains("invalid_response"))
    }

    @Test
    fun knownFailureReturnsNullWithoutSendingEnvelope() {
        val failure = PutioFailure.NetworkUnavailable(IllegalStateException("offline"))

        assertNull(PutioFailureReports.idFor(failure))
        Sentry.flush(FLUSH_MILLIS)

        assertTrue(received.isEmpty())
    }

    @Test
    fun recordedFailureReusesRecordedIdWithoutSendingAnotherEnvelope() {
        val failure = PutioFailure.Unexpected(IllegalStateException("recorded elsewhere"))
        val recordedId = "0f8fad5bd9cb469fa165708677289ba1"

        PutioFailureReports.record(failure, recordedId)
        val resolved = PutioFailureReports.idFor(failure)
        Sentry.flush(FLUSH_MILLIS)

        assertEquals(recordedId, resolved)
        assertEquals(recordedId, PutioFailureReports.reported(failure))
        assertTrue(received.isEmpty())
    }

    private fun awaitEnvelope(snippet: String) {
        val deadline = System.currentTimeMillis() + FLUSH_MILLIS
        while (received.none { snippet in it } && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        assertTrue("Expected envelope containing $snippet", received.any { snippet in it })
    }

    private object Connected : IConnectionStatusProvider {
        override fun getConnectionStatus() = IConnectionStatusProvider.ConnectionStatus.CONNECTED

        override fun getConnectionType(): String? = null

        override fun addConnectionStatusObserver(observer: IConnectionStatusProvider.IConnectionStatusObserver) = true

        override fun removeConnectionStatusObserver(observer: IConnectionStatusProvider.IConnectionStatusObserver) =
            Unit

        override fun close() = Unit
    }

    private companion object {
        val CONFIGURATION = SentryConfiguration.of("https://synthetickey@o0.example.invalid/1", "n", "mobile")
        const val FLUSH_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
