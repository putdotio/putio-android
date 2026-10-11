package io.putdotio.android.telemetry

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
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
        val configuration = SentryConfiguration.of("https://synthetickey@o0.example.invalid/1", "n", "tv")
        PutioDiagnostics.start(application, configuration)
        assertNotNull("no answer yet counts as on", SentryBoundary.current)

        PutioDiagnostics.update(allowed = false)
        PutioDiagnostics.start(application, configuration)

        assertNull("a cold launch ignored the stored opt-out", SentryBoundary.current)
    }

    @Implements(SentryAndroid::class)
    object NoSentry {
        @Suppress("UnusedParameter") // Robolectric matches a shadow by the shadowed method's signature.
        @JvmStatic
        @Implementation
        fun init(context: Context, configuration: Sentry.OptionsConfiguration<SentryAndroidOptions>) = Unit
    }
}
