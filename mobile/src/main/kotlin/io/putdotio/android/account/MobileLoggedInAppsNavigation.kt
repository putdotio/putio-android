package io.putdotio.android.account

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

internal fun NavHostController.loggedInAppsOpener(controller: LoggedInAppsController?): (() -> Unit)? =
    controller?.let { { navigate(MOBILE_LOGGED_IN_APPS_ROUTE) { launchSingleTop = true } } }

internal fun NavGraphBuilder.loggedInAppsDestination(
    controller: LoggedInAppsController?,
    onAuthenticationRequired: suspend () -> Unit,
) {
    if (controller == null) return
    composable(MOBILE_LOGGED_IN_APPS_ROUTE) { MobileLoggedInAppsRoute(controller, onAuthenticationRequired) }
}
