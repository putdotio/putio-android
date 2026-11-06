package io.putdotio.android.telemetry

import android.app.Application
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.RequestDetails
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryItemType
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, no API calls, two runs: [aSignalEndsTheProcess] starts Sentry as a launch does and raises
 * SIGSEGV, which ends the process; [theNextLaunchSendsTheCrashAsCodes], run after it, answers for the
 * account and checks the event the transport receives. The transport stays on the device.
 *
 * Arguments: `putio.nativeCrash.enabled=true`.
 */
@RunWith(AndroidJUnit4::class)
class NativeCrashProofTest {
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
    private val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val files = DiagnosticsFiles(application)
    private val events = CopyOnWriteArrayList<String>()

    @Test
    fun aSignalEndsTheProcess() {
        assumeTrue("Native crash proof requires opt-in", arguments.getString("putio.nativeCrash.enabled") == "true")
        files.delete()
        launch()
        val integrations = Sentry.getCurrentScopes().options.integrations.map { it.javaClass.simpleName }
        assertTrue("The NDK integration is not installed: $integrations", "NdkIntegration" in integrations)
        Thread.sleep(SETTLE_MILLIS)
        Os.kill(Os.getpid(), OsConstants.SIGSEGV)
        Thread.sleep(SETTLE_MILLIS)
        fail("The process outlived SIGSEGV")
    }

    @Test
    fun theNextLaunchSendsTheCrashAsCodes() {
        assumeTrue("Native crash proof requires opt-in", arguments.getString("putio.nativeCrash.enabled") == "true")
        launch()
        SentryBoundary.confirm()
        val deadline = System.currentTimeMillis() + SEND_TIMEOUT_MILLIS
        while (events.none { "SIGSEGV" in it } && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        SentryBoundary.stop(files).get()

        val event = events.singleOrNull { "SIGSEGV" in it }
        Log.i(TAG, "RESULT ${event ?: events}")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("event", event) })
        checkNotNull(event) { "No native crash reached the transport: $events" }
        assertTrue(event, "\"signalhandler\"" in event)
        assertTrue(event, "\"instruction_addr\"" in event)
        assertTrue(event, "\"type\":\"elf\"" in event)
        assertEquals(event, emptyList<String>(), UNLISTED.filter(event::contains))
    }

    private fun launch() {
        val period = SentryBoundary.open(launch = true)
        SentryAndroid.init(application) { options ->
            SentryBoundary.configure(options, checkNotNull(CONFIGURATION), period, files)
            options.setTransportFactory(DeviceTransport())
        }
    }

    private inner class DeviceTransport : ITransportFactory {
        override fun create(options: SentryOptions, requestDetails: RequestDetails): ITransport =
            object : ITransport {
                override fun send(envelope: SentryEnvelope, hint: Hint) {
                    envelope.items
                        .filter { it.header.type == SentryItemType.Event }
                        .forEach { events += it.data.decodeToString() }
                }

                override fun flush(timeoutMillis: Long) = Unit

                override fun getRateLimiter(): RateLimiter? = null

                override fun close(isRestarting: Boolean) = Unit

                override fun close() = Unit
            }
    }

    private companion object {
        const val TAG = "NativeCrashProof"
        const val SETTLE_MILLIS = 3_000L
        const val SEND_TIMEOUT_MILLIS = 30_000L
        const val POLL_MILLIS = 250L
        val CONFIGURATION = SentryConfiguration.of("https://synthetickey@o0.example.invalid/1", "proof", "mobile")
        val UNLISTED = listOf("\"threads\"", "\"breadcrumbs\"", "/data/app", "/data/user", "\"debug_file\"", "\"value\"")
    }
}
