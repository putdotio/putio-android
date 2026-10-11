package io.putdotio.android.telemetry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PutioDiagnosticsTest {
    @Test
    fun allowedUntilAnAccountOptsOutAndAColdLaunchKeepsTheOptOut() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        PutioDiagnostics.start(application, configuration = null)
        assertTrue("no answer yet counts as allowed", PutioDiagnostics.allowed)

        PutioDiagnostics.update(allowed = false)
        PutioDiagnostics.start(application, configuration = null)

        assertFalse(PutioDiagnostics.allowed)
    }
}
