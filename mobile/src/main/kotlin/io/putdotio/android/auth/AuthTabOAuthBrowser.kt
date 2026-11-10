package io.putdotio.android.auth

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsService
import androidx.core.net.toUri

internal sealed interface OAuthBrowserLaunchResult {
    data object Launched : OAuthBrowserLaunchResult

    data object Unsupported : OAuthBrowserLaunchResult

    data class Failed(
        val cause: RuntimeException,
    ) : OAuthBrowserLaunchResult
}

internal sealed interface OAuthBrowserResult {
    data class Callback(
        val uri: String,
    ) : OAuthBrowserResult

    data object Cancelled : OAuthBrowserResult

    data object Failed : OAuthBrowserResult
}

/** Launches OAuth only through a package verified to implement AndroidX Auth Tab. */
internal class AuthTabOAuthBrowser internal constructor(
    context: Context,
    private val resolveProvider: (Context) -> String?,
    private val launchAuthTab: (Uri, String, String) -> Unit,
) {
    private val applicationContext = context.applicationContext

    constructor(
        context: Context,
        launcher: ActivityResultLauncher<Intent>,
    ) : this(
        context = context,
        resolveProvider = ::resolveAuthTabProvider,
        launchAuthTab = { authorizationUri, redirectScheme, provider ->
            buildPinnedAuthTabIntent(provider).launch(launcher, authorizationUri, redirectScheme)
        },
    )

    // PackageManager and activity-launch Binder calls can propagate RuntimeException;
    // preserve the platform cause so the auth flow can recover from a failed launch.
    @Suppress("TooGenericExceptionCaught")
    fun launch(authorization: OAuthLaunchResult.Ready): OAuthBrowserLaunchResult {
        return try {
            val authorizationUri = authorization.authorizationUrl.toUri()
            require(authorizationUri.scheme == HTTPS_SCHEME && !authorizationUri.host.isNullOrEmpty()) {
                "OAuth authorization URL must use HTTPS"
            }
            val provider = resolveProvider(applicationContext)
                ?: return OAuthBrowserLaunchResult.Unsupported
            launchAuthTab(authorizationUri, MOBILE_OAUTH_SCHEME, provider)
            OAuthBrowserLaunchResult.Launched
        } catch (error: RuntimeException) {
            OAuthBrowserLaunchResult.Failed(error)
        }
    }
}

internal fun buildPinnedAuthTabIntent(provider: String): AuthTabIntent =
    AuthTabIntent.Builder().build().also { authTab ->
        // AuthTabIntent permits a Custom Tabs fallback unless the verified
        // Auth Tab provider is made explicit.
        authTab.intent.setPackage(provider)
    }

internal fun classifyAuthTabResult(
    resultCode: Int,
    rawResultUri: String?,
): OAuthBrowserResult =
    when (resultCode) {
        AuthTabIntent.RESULT_OK -> rawResultUri
            ?.let(OAuthBrowserResult::Callback)
            ?: OAuthBrowserResult.Failed

        AuthTabIntent.RESULT_CANCELED -> OAuthBrowserResult.Cancelled
        else -> OAuthBrowserResult.Failed
    }

internal fun resolveAuthTabProvider(context: Context): String? {
    val packageManager = context.packageManager
    val defaultProvider = CustomTabsClient.getPackageName(context, null)
    // Any app may declare the Custom Tabs service; only a browser opens the pinned https sign-in page.
    val browsers = packageManager
        .queryIntentActivities(
            Intent(Intent.ACTION_VIEW, "https://".toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_ALL,
        )
        .mapTo(HashSet()) { it.activityInfo.packageName }
    val installedProviders = packageManager
        .queryIntentServices(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), 0)
        .map { it.serviceInfo.packageName }
        .filter { it in browsers }
    return (listOfNotNull(defaultProvider) + installedProviders)
        .distinct()
        .firstOrNull { CustomTabsClient.isAuthTabSupported(context, it) }
}

private const val HTTPS_SCHEME = "https"
