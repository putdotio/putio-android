package io.putdotio.android.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CustomTabsOAuthBrowserTest {
    @Test
    fun `browser launch failures are returned to the auth flow`() {
        val failure = ActivityNotFoundException("no browser")
        val browser = CustomTabsOAuthBrowser { _, _ -> throw failure }
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        val result = browser.launch(
            activity = activity,
            authorization = OAuthLaunchResult.Ready("https://app.put.io/authenticate"),
        )

        assertTrue(result is OAuthBrowserLaunchResult.Failed)
        assertSame(failure, (result as OAuthBrowserLaunchResult.Failed).cause)
    }

    @Test
    fun `non HTTPS authorization URL fails without invoking Android`() {
        var launched = false
        val browser = CustomTabsOAuthBrowser { _, _ -> launched = true }
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        val result = browser.launch(
            activity = activity,
            authorization = OAuthLaunchResult.Ready("http://app.put.io/authenticate"),
        )

        assertTrue(result is OAuthBrowserLaunchResult.Failed)
        assertFalse(launched)
    }
}
