package io.putdotio.android.auth

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

sealed interface OAuthBrowserLaunchResult {
    data object Launched : OAuthBrowserLaunchResult

    data class Failed(
        val cause: RuntimeException,
    ) : OAuthBrowserLaunchResult
}

/** UI adapter for the URL emitted by [MobileAuthController.beginSignIn]. */
class CustomTabsOAuthBrowser internal constructor(
    private val launchUrl: (Activity, Uri) -> Unit,
) {
    constructor() : this(
        launchUrl = { activity, authorizationUri ->
            CustomTabsIntent.Builder().build().launchUrl(activity, authorizationUri)
        },
    )

    fun launch(
        activity: Activity,
        authorization: OAuthLaunchResult.Ready,
    ): OAuthBrowserLaunchResult {
        return try {
            val authorizationUri = authorization.authorizationUrl.toUri()
            require(authorizationUri.scheme == HTTPS_SCHEME && !authorizationUri.host.isNullOrEmpty()) {
                "OAuth authorization URL must use HTTPS"
            }
            launchUrl(activity, authorizationUri)
            OAuthBrowserLaunchResult.Launched
        } catch (error: RuntimeException) {
            OAuthBrowserLaunchResult.Failed(error)
        }
    }
}

private const val HTTPS_SCHEME = "https"
