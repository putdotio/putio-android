package io.putdotio.android.account

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

internal fun NavGraphBuilder.loggedInAppsDestination(
    controller: LoggedInAppsController?,
    onAuthenticationRequired: suspend () -> Unit,
    frame: @Composable (content: @Composable () -> Unit) -> Unit,
) {
    if (controller == null) return
    composable(MOBILE_LOGGED_IN_APPS_ROUTE) {
        frame { MobileLoggedInAppsRoute(controller, onAuthenticationRequired) }
    }
}
