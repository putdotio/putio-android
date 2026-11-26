package io.putdotio.android.telemetry

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SentryManifestTest {
    @Test
    fun mergedManifestContainsNoSentryProviders() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val providers = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS)
            .providers
            .orEmpty()
            .map { it.name }
            .filter { it.startsWith("io.sentry.") }

        assertEquals(emptyList<String>(), providers)
    }
}
