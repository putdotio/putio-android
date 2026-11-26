package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.ProductAnalytics

internal fun analyticsScreen(
    destination: MobileDestination,
    isPlayback: Boolean,
    isTrash: Boolean,
    searchOpen: Boolean,
): AnalyticsEvent.Screen? =
    when {
        isPlayback -> AnalyticsEvent.Screen.Player
        isTrash -> AnalyticsEvent.Screen.Trash
        searchOpen && destination == MobileDestination.Files -> AnalyticsEvent.Screen.Search
        else -> when (destination) {
            MobileDestination.Files -> AnalyticsEvent.Screen.Files
            MobileDestination.History -> AnalyticsEvent.Screen.History
            MobileDestination.Transfers -> AnalyticsEvent.Screen.Transfers
            MobileDestination.Downloads -> null
            MobileDestination.Account -> AnalyticsEvent.Screen.Account
        }
    }

@Composable
internal fun ScreenViewedEffect(screen: AnalyticsEvent.Screen?, sessionKey: Any) {
    var sent by rememberSaveable(sessionKey) { mutableStateOf<AnalyticsEvent.Screen?>(null) }
    LaunchedEffect(screen, sessionKey) {
        if (screen != null && screen != sent) ProductAnalytics.capture(AnalyticsEvent.ScreenViewed(screen))
        sent = screen
    }
}
