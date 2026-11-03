package io.putdotio.android.telemetry

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], instrumentedPackages = ["io.sentry.android.core"], shadows = [PutioDiagnosticsTest.NoSentry::class])
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
        val deadline = System.currentTimeMillis() + PURGE_MILLIS
        while (kept.exists() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        assertFalse("an envelope kept at launch outlived the opt-out", kept.exists())
    }

    @Implements(SentryAndroid::class)
    object NoSentry {
        @Suppress("UnusedParameter") // Robolectric matches a shadow by the shadowed method's signature.
        @JvmStatic
        @Implementation
        fun init(context: Context, configuration: Sentry.OptionsConfiguration<SentryAndroidOptions>) = Unit
    }

    private companion object {
        val CONFIGURATION = SentryConfiguration.of("https://synthetickey@o0.example.invalid/1", "n", "tv")
        const val PURGE_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
