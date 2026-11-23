package io.putdotio.android.account

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.R
import io.putdotio.android.libraryNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LibraryNoticesTest {
    @Test
    fun everyBundledLibraryShowsItsLicenseAndALink() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        val definitions = resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }

        val libraries = libraryNotices(definitions)

        assertEquals("Apache-2.0", libraries.single { it.id == "com.squareup.okhttp3:okhttp" }.license)
        assertEquals("MIT", libraries.single { it.id == "com.posthog:posthog-android" }.license)
        assertTrue(libraries.all { it.license.isNotEmpty() && it.url != null })
        assertEquals(libraries.size, libraries.distinctBy { it.name to it.version }.size)
    }
}
