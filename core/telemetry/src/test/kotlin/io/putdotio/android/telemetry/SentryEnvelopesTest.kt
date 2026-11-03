package io.putdotio.android.telemetry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SentryEnvelopesTest {
    private val received = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val root = Files.createTempDirectory("diagnostics").toFile()
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
    }

    @After
    fun close() {
        Sentry.close()
        server.stop(0)
    }

    @Test
    fun aLaunchSendsOnlyTheAllowlistedEventAfterTheAccountAnswers() {
        val period = SentryBoundary.open(launch = true)
        SentryAndroid.init(ApplicationProvider.getApplicationContext<Application>()) { options ->
            SentryBoundary.configure(options, checkNotNull(CONFIGURATION), period, files)
            options.dsn = "http://synthetickey@127.0.0.1:${server.address.port}/1"
        }

        Sentry.captureException(IOException("synthetic"))
        Sentry.flush(FLUSH_MILLIS)
        assertEquals("an envelope left before the account answered", emptyList<String>(), received)

        SentryBoundary.confirm()
        val deadline = System.currentTimeMillis() + FLUSH_MILLIS
        while (received.none { "IOException" in it } && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)

        assertTrue("the kept crash never left after the answer", received.any { "IOException" in it })
        assertFalse("a release-health session left the device", received.any { "\"type\":\"session\"" in it })
        assertTrue(received.single { "IOException" in it }.contains(files.installation.readText()))
    }

    private companion object {
        val CONFIGURATION = SentryConfiguration.of("https://synthetickey@o0.example.invalid/1", "n", "tv")
        const val FLUSH_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
