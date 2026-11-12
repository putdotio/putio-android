package io.putdotio.android

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.putdotio.android.account.MobileAccountPage
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_ROUTE

/** The tabs, in the order DESIGN.md's Layout fixes. Each keeps its own back stack. */
internal enum class MobileDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    Files(
        route = "files",
        labelRes = R.string.mobile_destination_files,
        icon = R.drawable.ic_ph_folder,
        selectedIcon = DesignR.drawable.ic_ph_folder_fill,
    ),
    History(
        route = "history",
        labelRes = R.string.mobile_destination_history,
        icon = R.drawable.ic_ph_clock_counter_clockwise,
        selectedIcon = R.drawable.ic_ph_clock_counter_clockwise_fill,
    ),
    Transfers(
        route = "transfers",
        labelRes = R.string.mobile_destination_transfers,
        icon = R.drawable.ic_ph_cloud_arrow_down,
        selectedIcon = R.drawable.ic_ph_cloud_arrow_down_fill,
    ),
    Downloads(
        route = MOBILE_DOWNLOADS_ROUTE,
        labelRes = R.string.mobile_destination_downloads,
        icon = R.drawable.ic_ph_download_simple,
        selectedIcon = R.drawable.ic_ph_download_simple_fill,
    ),
    Account(
        route = "account",
        labelRes = R.string.mobile_destination_account,
        icon = R.drawable.ic_ph_user_circle,
        selectedIcon = R.drawable.ic_ph_user_circle_fill,
    ),
    ;

    companion object {
        val start: MobileDestination = Files

        /** The tab a route belongs to; null for playback, which plays over whichever tab opened it. */
        fun ofRoute(route: String?): MobileDestination? =
            if (MobileAccountPage.ofRoute(route) != null) Account else entries.firstOrNull { it.route == route }

        fun fromRoute(route: String?): MobileDestination = ofRoute(route) ?: start

        fun shown(historyShown: Boolean): List<MobileDestination> =
            entries.filter { it != History || historyShown }
    }
}
