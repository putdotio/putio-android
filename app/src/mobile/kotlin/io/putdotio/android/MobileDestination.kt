package io.putdotio.android

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

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
        selectedIcon = R.drawable.ic_ph_folder_fill,
    ),
    Search(
        route = "search",
        labelRes = R.string.mobile_destination_search,
        icon = R.drawable.ic_ph_magnifying_glass,
        selectedIcon = R.drawable.ic_ph_magnifying_glass_fill,
    ),
    Transfers(
        route = "transfers",
        labelRes = R.string.mobile_destination_transfers,
        icon = R.drawable.ic_ph_arrow_circle_down,
        selectedIcon = R.drawable.ic_ph_arrow_circle_down_fill,
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

        fun fromRoute(route: String?): MobileDestination =
            if (route == MOBILE_TRASH_ROUTE) Account else entries.firstOrNull { it.route == route } ?: start
    }
}
