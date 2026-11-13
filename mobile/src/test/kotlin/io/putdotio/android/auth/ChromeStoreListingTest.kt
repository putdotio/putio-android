package io.putdotio.android.auth

import android.app.Activity
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChromeStoreListingTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Before
    fun failUnresolvedLaunches() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
    }

    @Test
    fun `Google Play opens the Chrome listing through its market link`() {
        registerHandler(PLAY_STORE, "market")
        registerHandler(PLAY_STORE, "https")

        assertTrue(openChromeStoreListing(activity))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Uri.parse(MARKET_LISTING), started.data)
        assertEquals(PLAY_STORE, started.`package`)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `Google Play without a market handler falls back to its web listing`() {
        registerHandler(PLAY_STORE, "https")

        assertTrue(openChromeStoreListing(activity))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Uri.parse(WEB_LISTING), started.data)
        assertEquals(PLAY_STORE, started.`package`)
    }

    @Test
    fun `a store other than Google Play is never handed the Chrome listing`() {
        registerHandler(OTHER_STORE, "market")
        registerHandler(BROWSER, "https")

        assertFalse(openChromeStoreListing(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `no store at all reports that nothing opened`() {
        assertFalse(openChromeStoreListing(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a Google Play that refuses the launch reports that nothing opened`() {
        val refusing = object : ContextWrapper(activity) {
            override fun startActivity(intent: Intent) = throw SecurityException("blocked by policy")
        }

        assertFalse(openChromeStoreListing(refusing))
    }

    private fun registerHandler(packageName: String, scheme: String) {
        val packageManager = shadowOf(activity.packageManager)
        val component = ComponentName(packageName, "$packageName.$scheme.ViewActivity")
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme(scheme)
            },
        )
    }

    private companion object {
        const val PLAY_STORE = "com.android.vending"
        const val OTHER_STORE = "com.amazon.venezia"
        const val BROWSER = "com.example.browser"
        const val MARKET_LISTING = "market://details?id=com.android.chrome"
        const val WEB_LISTING = "https://play.google.com/store/apps/details?id=com.android.chrome"
    }
}
