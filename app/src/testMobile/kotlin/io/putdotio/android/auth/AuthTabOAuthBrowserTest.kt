package io.putdotio.android.auth

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthTabOAuthBrowserTest {
    @Test
    fun `supported provider is pinned with the custom redirect scheme`() {
        var launch: Triple<Uri, String, String>? = null
        val browser = browser(
            resolveProvider = { "com.example.browser" },
            launchAuthTab = { uri, scheme, provider -> launch = Triple(uri, scheme, provider) },
        )

        val result = browser.launch(OAuthLaunchResult.Ready(AUTHORIZATION_URL))

        assertEquals(OAuthBrowserLaunchResult.Launched, result)
        assertEquals(
            Triple(Uri.parse(AUTHORIZATION_URL), "putio", "com.example.browser"),
            launch,
        )
    }

    @Test
    fun `Auth Tab intent is explicitly bound to the verified provider`() {
        val authTab = buildPinnedAuthTabIntent("com.example.browser")

        assertEquals("com.example.browser", authTab.intent.`package`)
    }

    @Test
    fun `missing Auth Tab provider fails closed without launching`() {
        var launched = false
        val browser = browser(
            resolveProvider = { null },
            launchAuthTab = { _, _, _ -> launched = true },
        )

        val result = browser.launch(OAuthLaunchResult.Ready(AUTHORIZATION_URL))

        assertEquals(OAuthBrowserLaunchResult.Unsupported, result)
        assertFalse(launched)
    }

    @Test
    fun `default Custom Tabs provider with Auth Tab category is selected`() {
        registerDefaultCustomTabsProvider(authTabSupported = true)

        assertEquals(BROWSER_PACKAGE, resolveAuthTabProvider(applicationContext))
    }

    @Test
    fun `default Custom Tabs provider without Auth Tab category is rejected`() {
        registerDefaultCustomTabsProvider(authTabSupported = false)

        assertNull(resolveAuthTabProvider(applicationContext))
    }

    @Test
    fun `non HTTPS authorization URL fails before provider discovery`() {
        var providerResolved = false
        val browser = browser(
            resolveProvider = {
                providerResolved = true
                "com.example.browser"
            },
        )

        val result = browser.launch(OAuthLaunchResult.Ready("http://app.put.io/authenticate"))

        assertTrue(result is OAuthBrowserLaunchResult.Failed)
        assertFalse(providerResolved)
    }

    @Test
    fun `Auth Tab launch exception is returned to the auth flow`() {
        val failure = ActivityNotFoundException("browser disappeared")
        val browser = browser(
            resolveProvider = { "com.example.browser" },
            launchAuthTab = { _, _, _ -> throw failure },
        )

        val result = browser.launch(OAuthLaunchResult.Ready(AUTHORIZATION_URL))

        assertTrue(result is OAuthBrowserLaunchResult.Failed)
        assertSame(failure, (result as OAuthBrowserLaunchResult.Failed).cause)
    }

    @Test
    fun `Auth Tab results distinguish callback cancellation and failure`() {
        assertEquals(
            OAuthBrowserResult.Callback("putio://auth#state=s&access_token=t"),
            classifyAuthTabResult(
                AuthTabIntent.RESULT_OK,
                "putio://auth#state=s&access_token=t",
            ),
        )
        assertEquals(
            OAuthBrowserResult.Cancelled,
            classifyAuthTabResult(AuthTabIntent.RESULT_CANCELED, null),
        )
        listOf(
            AuthTabIntent.RESULT_VERIFICATION_FAILED,
            AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT,
            AuthTabIntent.RESULT_UNKNOWN_CODE,
        ).forEach { resultCode ->
            assertEquals(OAuthBrowserResult.Failed, classifyAuthTabResult(resultCode, null))
        }
        assertEquals(OAuthBrowserResult.Failed, classifyAuthTabResult(AuthTabIntent.RESULT_OK, null))
    }

    private fun browser(
        resolveProvider: (Context) -> String?,
        launchAuthTab: (Uri, String, String) -> Unit = { _, _, _ -> },
    ): AuthTabOAuthBrowser =
        AuthTabOAuthBrowser(
            context = RuntimeEnvironment.getApplication(),
            resolveProvider = resolveProvider,
            launchAuthTab = launchAuthTab,
        )

    private fun registerDefaultCustomTabsProvider(authTabSupported: Boolean) {
        val packageManager = shadowOf(applicationContext.packageManager)
        val browserActivity = ComponentName(BROWSER_PACKAGE, "$BROWSER_PACKAGE.BrowserActivity")
        packageManager.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = browserActivity.packageName
                name = browserActivity.className
            },
        )
        packageManager.addIntentFilterForActivity(
            browserActivity,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
            },
        )

        val customTabsService = ComponentName(BROWSER_PACKAGE, "$BROWSER_PACKAGE.CustomTabsService")
        packageManager.addOrUpdateService(
            ServiceInfo().apply {
                packageName = customTabsService.packageName
                name = customTabsService.className
            },
        )
        packageManager.addIntentFilterForService(
            customTabsService,
            IntentFilter(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).apply {
                if (authTabSupported) {
                    addCategory(AUTH_TAB_CATEGORY)
                }
            },
        )
    }

    private val applicationContext: Context
        get() = RuntimeEnvironment.getApplication()

    private companion object {
        const val AUTHORIZATION_URL = "https://app.put.io/authenticate"
        const val AUTH_TAB_CATEGORY = "androidx.browser.auth.category.AuthTab"
        const val BROWSER_PACKAGE = "com.example.browser"
    }
}
