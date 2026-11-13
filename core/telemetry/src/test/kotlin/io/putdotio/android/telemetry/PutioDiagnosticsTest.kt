package io.putdotio.android.telemetry

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [35],
    instrumentedPackages = ["io.sentry.android.core"],
    shadows = [PutioDiagnosticsTest.RecordingSentry::class],
)
class PutioDiagnosticsTest {
    @Test
    fun onUntilAnAccountOptsOutAndAColdLaunchKeepsTheOptOut() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        PutioDiagnostics.start(application, CONFIGURATION)
        assertNotNull("no answer yet counts as on", SentryBoundary.current)

        PutioDiagnostics.update(allowed = false)
        PutioDiagnostics.start(application, CONFIGURATION)

        assertNull("a cold launch ignored the stored opt-out", SentryBoundary.current)
    }

    @Test
    fun aLaunchSendsOnlyOnceTheAccountAllowsIt() {
        PutioDiagnostics.start(ApplicationProvider.getApplicationContext<Application>(), CONFIGURATION)
        assertEquals("a launch sent before the account answered", false, SentryBoundary.current?.confirmed)

        PutioDiagnostics.update(allowed = true)

        assertEquals(true, SentryBoundary.current?.confirmed)
    }

    @Test
    fun anOptOutMadeElsewhereDeletesWhatTheLaunchKept() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        PutioDiagnostics.start(application, CONFIGURATION)
        val kept = File(DiagnosticsFiles(application).cache, "kept.envelope").apply { parentFile?.mkdirs() }
        kept.writeText("synthetic")

        PutioDiagnostics.update(allowed = false)

        assertNull(SentryBoundary.current)
        awaitDeleted(kept, "an envelope kept at launch outlived the opt-out")
    }

    @Test
    fun signingOutBeforeTheAccountAnswersDeletesWhatTheLaunchKept() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        PutioDiagnostics.start(application, CONFIGURATION)
        PutioDiagnostics.update(DiagnosticsAnswer.Account(true))
        PutioDiagnostics.start(application, CONFIGURATION)
        val kept = File(DiagnosticsFiles(application).cache, "kept.envelope").apply { parentFile?.mkdirs() }
        kept.writeText("synthetic")

        PutioDiagnostics.update(DiagnosticsAnswer.SignedOut)

        assertEquals("signed out kept diagnostics off", true, SentryBoundary.current?.confirmed)
        assertNotEquals("signed out reused the launch period", 0L, SentryBoundary.current?.since)
        awaitDeleted(kept, "a signed-out answer released an envelope the previous account kept")
    }

    @Test
    fun aCrashFromASignedOutSessionStillLeavesOnceSignedOutAgain() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        PutioDiagnostics.start(application, CONFIGURATION)
        PutioDiagnostics.update(DiagnosticsAnswer.SignedOut)
        PutioDiagnostics.start(application, CONFIGURATION)
        val kept = File(DiagnosticsFiles(application).cache, "kept.envelope").apply { parentFile?.mkdirs() }
        kept.writeText("synthetic")

        PutioDiagnostics.update(DiagnosticsAnswer.SignedOut)

        assertEquals("a signed-out launch did not confirm", true, SentryBoundary.current?.confirmed)
        assertEquals("a signed-out launch restarted its period", 0L, SentryBoundary.current?.since)
        assertTrue("a signed-out crash was deleted", kept.exists())
    }

    @Test
    fun aSessionAnOlderBuildLeftIsWipedOffTheLaunchThreadBeforeSentryStarts() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val session = File(DiagnosticsFiles(application).cache, "dsn/previous_session.json")
        session.parentFile?.mkdirs()
        session.writeText("synthetic")
        val queued = ArrayDeque<Runnable>()
        val background = SentryBoundary.executor
        SentryBoundary.executor = Executor(queued::addLast)
        RecordingSentry.sessionAtInit.clear()
        try {
            PutioDiagnostics.start(application, CONFIGURATION)

            assertTrue("the launch thread wiped the cache", session.exists())
            assertEquals("Sentry started on the launch thread", emptyList<Boolean>(), RecordingSentry.sessionAtInit)

            while (queued.isNotEmpty()) queued.removeFirst().run()
        } finally {
            SentryBoundary.executor = background
        }

        assertFalse("a session from an older build waited for the answer", session.exists())
        assertEquals("Sentry started before the wipe", listOf(false), RecordingSentry.sessionAtInit)
    }

    @Test
    fun aLaunchAfterTheWipeStartsSentryOnTheLaunchThread() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        PutioDiagnostics.start(application, CONFIGURATION)
        SentryBoundary.submit {}.get()
        val background = SentryBoundary.executor
        SentryBoundary.executor = Executor { error("a later launch queued work") }
        RecordingSentry.sessionAtInit.clear()
        try {
            PutioDiagnostics.start(application, CONFIGURATION)
        } finally {
            SentryBoundary.executor = background
        }

        assertEquals(1, RecordingSentry.sessionAtInit.size)
    }

    private fun awaitDeleted(file: File, message: String) {
        val deadline = System.currentTimeMillis() + PURGE_MILLIS
        while (file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        assertFalse(message, file.exists())
    }

    @Implements(SentryAndroid::class)
    object RecordingSentry {
        val sessionAtInit: MutableList<Boolean> = CopyOnWriteArrayList()

        @Suppress("UnusedParameter") // Robolectric matches a shadow by the shadowed method's signature.
        @JvmStatic
        @Implementation
        fun init(context: Context, configuration: Sentry.OptionsConfiguration<SentryAndroidOptions>) {
            sessionAtInit += File(DiagnosticsFiles(context).cache, "dsn/previous_session.json").exists()
        }
    }

    private companion object {
        val CONFIGURATION = SentryConfiguration.of("https://synthetickey@o0.example.invalid/1", "n", "tv")
        const val PURGE_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
